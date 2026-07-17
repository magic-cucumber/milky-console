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
    require(args.size >= 3) { "usage: loader <sink-fd> <source-fd> <library> [config]" }
    val sink = IPCAnonymousPipe.fromSink(args[0].toULong())
    val source = IPCAnonymousPipe.fromSource(args[1].toULong())
    val libpath = args[2]
    val config = args[3] // "{}"
    val base = args[4]

    if (chdir(base) != 0) {
        exit(-1)
    }

    // send_message is synchronous: serialize direct callback writes with the
    // regular EventBus sender so returning means the request is already in the pipe.
    val terminalResultWritten = CompletableDeferred<Unit>()
    val pipeWriteLock = AtomicBoolean(false)
    val writeEvent: (MilkyConsoleFromEvent.FromPlugin) -> Unit = { event ->
        while (!pipeWriteLock.compareAndSet(false, true)) {
            // Pipe writes are short and contention only occurs with the single sender.
        }
        try {
            event.toPacket().forEach(sink::writePacket)
        } finally {
            pipeWriteLock.store(false)
        }
    }
    val pipeWriterDispatcher = newSingleThreadContext("plugin-pipe-writer")
    val pipeReaderDispatcher = newSingleThreadContext("plugin-pipe-reader")
    val sender = launch(pipeWriterDispatcher, start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<MilkyConsoleFromEvent.FromPlugin>().collect { event ->
            writeEvent(event)
            if (event is PluginHandshakeResult) terminalResultWritten.complete(Unit)
        }
    }

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
                packet
            } else {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    packetsByGroup.put(group, packets)
                    continue
                }
                val ordered = packets.filter { it.index != null }.sortedBy { it.index }
                if (!ordered.indices.all { ordered[it].index == it }) {
                    packetsByGroup.put(group, packets)
                    continue
                }
                packetsByGroup.remove(group)
                ordered.merge()
            }
            val event = merged.data.readContent<MilkyConsoleFromEvent.FromHost>()
            if (event is PluginApiResponse) {
                PendingPluginApiRequests.complete(event)
            } else {
                EventBus.postBlocking(event)
            }
        }
    }

    suspend fun reject(message: String, error: PluginHandshakeError? = null): Nothing {
        EventBus.post(PluginHandshakeResult.Rejected(message, error))
        terminalResultWritten.await()
        receiver.cancel()
        sender.cancel()
        pipeReaderDispatcher.close()
        pipeWriterDispatcher.close()
        sink.close()
        source.close()
        exit(1)
        error("unreachable")
    }

    // Sender and HostHandshakeRequest listener are now both active.
    EventBus.post(PluginHandshakeRequest)
    if (withTimeoutOrNull(10.seconds) { hostHandshakeRequest.await() } == null) {
        reject("10秒内没有收到握手包，取消握手", PluginHandshakeError.TIMEOUT)
    }

    val loader = try {
        DLLoader(libpath)
    } catch (e: Throwable) {
        reject("无法加载插件动态库: ${e.message}", PluginHandshakeError.DYNAMIC_LIBRARY_LOAD_FAILED)
    }
    val getApi = try {
        loader.findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
    } catch (e: Throwable) {
        loader.close()
        reject("无法查找到插件加载函数: ${e.message}", PluginHandshakeError.ENTRY_POINT_NOT_FOUND)
    }
    val pointer = getApi.invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
    if (pointer == null || pointer.rawValue == NativePtr.NULL) {
        reject(
            "无法查找到插件加载函数: 插件加载函数返回值为空指针",
            PluginHandshakeError.NULL_PLUGIN_API,
        )
    }
    val api = pointer.pointed

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        reject(
            "插件ABI不匹配。本加载器期望: $MILKY_CONSOLE_HOST_ABI_VERSION，该插件的ABI为${api.abi_version}",
            PluginHandshakeError.ABI_MISMATCH,
        )
    }
    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        reject("插件API不匹配。", PluginHandshakeError.API_MISMATCH)
    }
    if (api.on_load == null) {
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
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }
        val text = message?.toKString() ?: return@staticCFunction cValue {
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }

        val event = try {
            text.toPluginApiRequest(type)!!
        } catch (_: Throwable) {
            return@staticCFunction cValue {
                uuid[0] = 0
                result = MILKY_RESULT_INVALID_ARGUMENT
            }
        }

        if (!PendingPluginApiRequests.register(event)) {
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
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }
        val uuid = try {
            Uuid.parse(id.toKString())
        } catch (_: IllegalArgumentException) {
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }

        val deferred = PendingPluginApiRequests.get(uuid) ?: return@staticCFunction cValue {
            result = MILKY_RESULT_INVALID_ARGUMENT
            required_size = 0u
        }

        val result = runBlocking {
            withTimeoutOrNull(timeout.milliseconds) {
                deferred.await()
            }
        }

        if (result == null) {
            return@staticCFunction cValue {
                this.result = MILKY_RESULT_TIMEOUT
                required_size = 0u
            }
        }

        milkyJsonModule.encodeToString(result.payload).encodeToByteArray().usePinned {
            val byteCount = it.get().size
            if (size < byteCount.toUInt() + 1u) {
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
    Logger.withTag("PluginLoader").d { "dispatching on_load" }
    val loaded = withContext(callbackDispatcher) {
        memScoped {
            api.on_load!!.invoke(config.cstr.getPointer(this), hostApi)
        }
    }
    Logger.withTag("PluginLoader").d { "on_load completed with result=$loaded" }
    PendingPluginApiRequests.lastSendFailure()?.let {
        Logger.withTag("PluginLoader").e { "send_message failed:\n$it" }
    }
    if (loaded == MILKY_FALSE) {
        callbackDispatcher.close()
        free(hostApi)
        reject("插件初始化失败", PluginHandshakeError.INITIALIZATION_FAILED)
    }

    // Ready is sent only after all runtime listeners are registered.
    val hostEvents = launch(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostEvent>().collect { message ->
            Logger.withTag("PluginLoader").d { "dispatching on_message" }
            withContext(callbackDispatcher) {
                memScoped {
                    api.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
                }
            }
            Logger.withTag("PluginLoader").d { "on_message completed" }
        }
    }
    val hostClose = async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostClose>().first()
    }
    EventBus.post(PluginHandshakeResult.Ready)
    terminalResultWritten.await()

    hostClose.await()
    hostEvents.cancelAndJoin()
    val exitCode = try {
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
    exit(exitCode)
}
