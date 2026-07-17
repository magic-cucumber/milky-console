import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.*
import platform.posix.chdir
import platform.posix.exit
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import top.kagg886.milky.console.protocol.*
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.fromSink
import top.kagg886.milky.console.util.pipe.fromSource
import top.kagg886.milky.console.util.protocol.*
import top.kagg886.milky.console.util.readContent
import top.kagg886.saltify.console.util.dlloader.DLLoader
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid

private val pluginLoaderLogger = Logger.withTag("PluginLoader")

/**
 *                 receivePipe.sink.fd.toString(),
 *                 sendPipe.source.fd.toString(),
 *                 verified.libpath.toString(),
 *                 Json.encodeToString(verified.config),
 *                 registry.pluginDataPath(this@handshake).toString()
 */
@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalSerializationApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalAtomicApi::class,
)
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    val logger = pluginLoaderLogger
    logger.i { "enter main: argCount=${args.size}" }
    require(args.size >= 3) { "usage: loader <sink-fd> <source-fd> <library> [config]" }
    val sink = IPCAnonymousPipe.fromSink(args[0].toULong())
    val source = IPCAnonymousPipe.fromSource(args[1].toULong())
    val libpath = args[2]
    val config = args[3] // "{}"
    val base = args[4]

    if (chdir(base) != 0) {
        logger.e { "working directory setup failed: base=$base" }
        exit(-1)
    }
    logger.d { "runtime context initialized: sink=${args[0]}, source=${args[1]}, library=$libpath, base=$base" }

    // send_message is synchronous: serialize direct callback writes with the
    // regular EventBus sender so returning means the request is already in the pipe.
    val terminalResultWritten = CompletableDeferred<Unit>()
    val pipeWriteLock = AtomicBoolean(false)
    val writeEvent: (MilkyConsoleFromEvent.FromPlugin) -> Unit = { event ->
        while (!pipeWriteLock.compareAndSet(expectedValue = false, newValue = true)) {
            // Pipe writes are short and contention only occurs with the single sender.
        }
        try {
            event.toPacket().forEach(sink::writePacket)
            if (event !is PluginLog) {
                logger.v { "wrote plugin event to host: type=${event::class.simpleName}" }
            }
        } finally {
            pipeWriteLock.store(false)
        }
    }
    val pipeWriterDispatcher = newSingleThreadContext("plugin-pipe-writer")
    val pipeReaderDispatcher = newSingleThreadContext("plugin-pipe-reader")
    val senderSubscribed = CompletableDeferred<Unit>()
    val sender = launch(pipeWriterDispatcher, start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<MilkyConsoleFromEvent.FromPlugin> { senderSubscribed.complete(Unit) }.collect { event ->
            writeEvent(event)
            if (event is PluginHandshakeResult) {
                logger.d { "terminal handshake result written: ${event::class.simpleName}" }
                terminalResultWritten.complete(Unit)
            } else {
                if (event is PluginLog) return@collect
                logger.v { "non-terminal plugin event written: ${event::class.simpleName}" }
            }
        }
    }
    senderSubscribed.await()

    Logger.setLogWriters(object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            val text = throwable?.let { "$message\n${it.stackTraceToString()}" } ?: message
            EventBus.tryPost(PluginLog(severity.ordinal, tag, text))
        }
    })

    // This is the readiness condition represented by PluginHandshakeRequest.
    val hostHandshakeRequest = async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostHandshakeRequest>().first()
    }

    val receiver = launch(pipeReaderDispatcher) {
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = source.readPacket()
            val merged = if (!packet.isSplit) {
                logger.v { "received complete host packet: bytes=${packet.data.size}" }
                packet
            } else {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    logger.d { "stored host split packet: group=$group, received=${packets.size}/${packet.size}" }
                    packetsByGroup.put(group, packets)
                    continue
                }
                val ordered = packets.filter { it.index != null }.sortedBy { it.index }
                if (!ordered.indices.all { ordered[it].index == it }) {
                    logger.w { "host split packet indexes incomplete: group=$group" }
                    packetsByGroup.put(group, packets)
                    continue
                }
                packetsByGroup.remove(group)
                ordered.merge()
            }
            val event = merged.data.readContent<MilkyConsoleFromEvent.FromHost>()
            if (event is PluginApiResponse) {
                logger.d { "completed pending API response: tag=${event.tag}" }
                PendingPluginApiRequests.complete(event)
            } else {
                logger.d { "dispatching host event: type=${event::class.simpleName}" }
                EventBus.postBlocking(event)
            }
        }
    }

    suspend fun reject(message: String, error: PluginHandshakeError? = null): Nothing {
        logger.e { "rejecting loader flow: message=$message, error=$error" }
        EventBus.post(PluginHandshakeResult.Rejected(message, error))
        terminalResultWritten.await()
        receiver.cancel()
        sender.cancel()
        pipeReaderDispatcher.close()
        pipeWriterDispatcher.close()
        sink.close()
        source.close()
        exit(1)
        logger.a { "unreachable after exit(1)" }
        error("unreachable")
    }

    // Sender and HostHandshakeRequest listener are now both active.
    EventBus.post(PluginHandshakeRequest)
    logger.i { "sent PluginHandshakeRequest; waiting for host listener" }
    if (withTimeoutOrNull(10.seconds) { hostHandshakeRequest.await() } == null) {
        logger.e { "host handshake request timed out" }
        reject("10秒内没有收到握手包，取消握手", PluginHandshakeError.TIMEOUT)
    }

    val loader = try {
        logger.d { "loading plugin dynamic library: $libpath" }
        DLLoader(libpath)
    } catch (e: Throwable) {
        logger.e { "dynamic library load failed: ${e.message}" }
        reject("无法加载插件动态库: ${e.message}", PluginHandshakeError.DYNAMIC_LIBRARY_LOAD_FAILED)
    }
    val getApi = try {
        logger.d { "resolving milky_plugin_get_api" }
        loader.findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
    } catch (e: Throwable) {
        logger.e { "entry point lookup failed: ${e.message}" }
        loader.close()
        reject("无法查找到插件加载函数: ${e.message}", PluginHandshakeError.ENTRY_POINT_NOT_FOUND)
    }
    val pointer = getApi.invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
    logger.d { "plugin API pointer returned: present=${pointer != null && pointer.rawValue != NativePtr.NULL}" }
    if (pointer == null || pointer.rawValue == NativePtr.NULL) {
        reject(
            "无法查找到插件加载函数: 插件加载函数返回值为空指针",
            PluginHandshakeError.NULL_PLUGIN_API,
        )
    }
    val api = pointer.pointed

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        logger.e { "ABI mismatch: host=$MILKY_CONSOLE_HOST_ABI_VERSION, plugin=${api.abi_version}" }
        reject(
            "插件ABI不匹配。本加载器期望: $MILKY_CONSOLE_HOST_ABI_VERSION，该插件的ABI为${api.abi_version}",
            PluginHandshakeError.ABI_MISMATCH,
        )
    }
    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        logger.e { "API struct too small: actual=${api.struct_size}, expected=${sizeOf<milky_console_plugin_api>()}" }
        reject("插件API不匹配。", PluginHandshakeError.API_MISMATCH)
    }
    if (api.on_load == null) {
        logger.e { "API missing on_load" }
        reject("插件API缺少 on_load", PluginHandshakeError.MISSING_ON_LOAD)
    }

    val hostApi = malloc(sizeOf<milky_console_host_api>().toULong())!!.reinterpret<milky_console_host_api>()
    PendingPluginApiRequests.initialize { request ->
        writeEvent(request)
        true
    }

    hostApi.pointed.abi_version = MILKY_CONSOLE_HOST_ABI_VERSION
    hostApi.pointed.struct_size = sizeOf<milky_console_host_api>().toUInt()
    hostApi.pointed.send_message = staticCFunction { type, message ->
        val type = type?.toKString() ?: return@staticCFunction cValue {
            pluginLoaderLogger.w { "send_message received null type; returning INVALID_ARGUMENT" }
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }
        val text = message?.toKString() ?: return@staticCFunction cValue {
            pluginLoaderLogger.w { "send_message received null message; returning INVALID_ARGUMENT" }
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }

        val event = try {
            text.toPluginApiRequest(type)!!
        } catch (_: Throwable) {
            pluginLoaderLogger.w { "send_message payload could not be decoded; returning INVALID_ARGUMENT" }
            return@staticCFunction cValue {
                uuid[0] = 0
                result = MILKY_RESULT_INVALID_ARGUMENT
            }
        }

        if (!PendingPluginApiRequests.register(event)) {
            pluginLoaderLogger.e { "send_message request registration failed: tag=${event.tag}" }
            return@staticCFunction cValue {
                uuid[0] = 0
                result = MILKY_RESULT_INTERNAL_ERROR
            }
        }
        return@staticCFunction cValue {
            event.tag.toString().encodeToByteArray().usePinned { bytes ->
                memcpy(
                    uuid,
                    bytes.addressOf(0),
                    bytes.get().size.convert()
                )
                uuid[bytes.get().size] = 0
            }
        }
    }
    hostApi.pointed.wait_message_result = staticCFunction { id, timeout, buffer, size ->
        if (id == null || timeout <= 0 || size <= 0u || buffer == null || id[36] != 0.toByte()) {
            pluginLoaderLogger.w { "wait_message_result received invalid arguments" }
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }
        val uuid = try {
            Uuid.parse(id.toKString())
        } catch (_: IllegalArgumentException) {
            pluginLoaderLogger.w { "wait_message_result received invalid UUID" }
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }

        val deferred = PendingPluginApiRequests.get(uuid) ?: return@staticCFunction cValue {
            pluginLoaderLogger.w { "wait_message_result has no pending request: tag=$uuid" }
            result = MILKY_RESULT_INVALID_ARGUMENT
            required_size = 0u
        }

        val result = runBlocking {
            withTimeoutOrNull(timeout.milliseconds) {
                deferred.await()
            }
        }

        if (result == null) {
            pluginLoaderLogger.w { "wait_message_result timed out: tag=$uuid, timeoutMs=$timeout" }
            return@staticCFunction cValue {
                this.result = MILKY_RESULT_TIMEOUT
                required_size = 0u
            }
        }

        milkyJsonModule.encodeToString(result.payload).encodeToByteArray().usePinned {
            val byteCount = it.get().size
            if (size < byteCount.toUInt() + 1u) {
                pluginLoaderLogger.w { "wait_message_result buffer too short: tag=$uuid, capacity=$size, required=${byteCount + 1}" }
                return@staticCFunction cValue {
                    this.result = MILKY_RESULT_BUFFER_TOO_SHORT
                    required_size = byteCount.toULong() + 1u
                }
            }

            memcpy(
                buffer,
                it.addressOf(0),
                byteCount.convert()
            )

            buffer[byteCount] = 0
            PendingPluginApiRequests.remove(uuid)

            return@staticCFunction cValue {
                this.result = MILKY_RESULT_OK
                required_size = byteCount.toULong() + 1u
            }
        }
    }

    val callbackDispatcher = newSingleThreadContext("plugin-callback")
    pluginLoaderLogger.d { "dispatching on_load" }
    val loaded = withContext(callbackDispatcher) {
        memScoped {
            api.on_load!!.invoke(config.cstr.getPointer(this), hostApi)
        }
    }
    pluginLoaderLogger.d { "on_load completed with result=$loaded" }
    logger.i { "on_load execution result: loaded=$loaded, pendingSendFailure=${PendingPluginApiRequests.lastSendFailure() != null}" }
    PendingPluginApiRequests.lastSendFailure()?.let {
        pluginLoaderLogger.e { "send_message failed:\n$it" }
    }
    if (loaded == MILKY_FALSE) {
        logger.e { "plugin on_load returned MILKY_FALSE" }
        callbackDispatcher.close()
        free(hostApi)
        reject("插件初始化失败", PluginHandshakeError.INITIALIZATION_FAILED)
    }

    // Ready is sent only after all runtime listeners are registered.
    val hostEvents = launch(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostEvent>().collect { message ->
            logger.d { "enter on_message callback: type=${message.event::class.simpleName}" }
            withContext(callbackDispatcher) {
                memScoped {
                    api.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
                }
            }
            logger.i { "exit on_message callback successfully: type=${message.event::class.simpleName}" }
        }
    }
    val hostClose = async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostClose>().first()
    }
    EventBus.post(PluginHandshakeResult.Ready)
    logger.i { "sent Ready; waiting for HostClose" }
    terminalResultWritten.await()

    hostClose.await()
    hostEvents.cancelAndJoin()
    val exitCode = try {
        logger.i { "enter on_unload callback" }
        withContext(callbackDispatcher) { api.on_unload?.invoke() ?: 0 }
    } finally {
        receiver.cancel()
        sender.cancel()
        pipeReaderDispatcher.close()
        pipeWriterDispatcher.close()
        free(hostApi)
        sink.close()
        source.close()
        loader.close()
        callbackDispatcher.close()
    }
    logger.i { "exit main successfully: plugin exitCode=$exitCode" }
    exit(exitCode)
}
