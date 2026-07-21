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
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.uuid.Uuid

private val logger = Logger.withTag("PluginLoader")
private val logTransportTags = setOf("ProtocolJson", "ProtocolPacket", "ProtocolPacketCodec", "UnixPipe", "WindowsPipe")

/**
```kotlin
receivePipe.sink.fd.toString(),
sendPipe.source.fd.toString(),
logPipe.sink.fd.toString(),
tmp.toString(),
Json.encodeToString(verified.config),
registry.pluginDataPath(this@handshake).toString(),
Logger.mutableConfig.minSeverity.name,
```
 */
@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalSerializationApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalAtomicApi::class,
    ExperimentalNativeApi::class,
)
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    Logger.setMinSeverity(Severity.valueOf(args[6]))
    logger.i { "enter main: argCount=${args.size}" }
    setUnhandledExceptionHook { throwable ->
        logger.a { "loader crashed before IPC crash reporter was ready: ${throwable.stackTraceToString()}" }
        exit(1)
    }
    if (args.size < 7) {
        logger.e { "exit main unsuccessfully: invalid arguments, expected at least 7 but got ${args.size}" }
        exit(1)
    }
    logger.v { "argument validation passed; initializing IPC endpoints" }
    val sink = IPCAnonymousPipe.fromSink(args[0].toULong())
    val source = IPCAnonymousPipe.fromSource(args[1].toULong())
    val logSink = IPCAnonymousPipe.fromSink(args[2].toULong())
    val libpath = args[3]
    val config = args[4] // "{}"
    val base = args[5]

    if (chdir(base) != 0) {
        logger.e { "working directory setup failed: base=$base" }
        exit(-1)
    }
    logger.d { "runtime context initialized: sink=${args[0]}, source=${args[1]}, logSink=${args[2]}, library=$libpath, base=$base" }
    logger.v { "creating business and log pipe write locks" }

    // send_message is synchronous: serialize direct callback writes with the
    // regular EventBus sender so returning means the request is already in the pipe.
    val terminalResultWritten = CompletableDeferred<Unit>()
    val pipeWriteLock = AtomicBoolean(false)
    val logPipeWriteLock = AtomicBoolean(false)
    val writeEvent: (MilkyConsoleFromEvent.FromPlugin) -> Unit = { event ->
        while (!pipeWriteLock.compareAndSet(expectedValue = false, newValue = true)) {
            // Pipe writes are short and contention only occurs with the single sender.
        }
        try {
            logger.v { "enter writeEvent: type=${event::class.simpleName}" }
            event.toPacket().forEach(sink::writePacket)
            logger.v { "wrote plugin event to host: type=${event::class.simpleName}" }
        } finally {
            pipeWriteLock.store(false)
            logger.v { "exit writeEvent: type=${event::class.simpleName}" }
        }
    }
    val writeLog: (PluginLog) -> Unit = { event ->
        // A log may span multiple packets. Keep the whole structured record under
        // one lock so concurrent callbacks cannot interleave multi-line output.
        while (!logPipeWriteLock.compareAndSet(expectedValue = false, newValue = true)) {
            // Log records are short; serialize concurrent callbacks at the pipe boundary.
        }
        try {
            event.toPacket().forEach(logSink::writePacket)
        } finally {
            logPipeWriteLock.store(false)
        }
    }
    // This is a last-resort path for Kotlin exceptions that would otherwise
    // reach a Kotlin/Native boundary and make the runtime abort with SIGABRT.
    // Write directly instead of using EventBus: its worker may be the one that
    // failed, while writeEvent is synchronous and serialized by pipeWriteLock.
    setUnhandledExceptionHook { throwable ->
        val message = "loader 未处理的 Kotlin 异常: ${throwable.message ?: throwable::class.simpleName}"
        logger.a { "loader crashed after IPC crash reporter was ready: ${throwable.stackTraceToString()}" }
        val reported = runCatching {
            val stacktrace = throwable.stackTraceToString()
            writeLog(PluginLog(Severity.Error.ordinal, "PluginLoader", message, stacktrace))
            writeEvent(PluginClosed(message, stacktrace))
        }.isSuccess
        // The loader cannot safely resume after an exception has escaped a
        // top-level or worker boundary. A successfully written PluginClosed is
        // a protocol-complete shutdown, so it exits with code 0; otherwise the
        // host did not receive the report and must treat it as a failure.
        logger.e { "exit main after unhandled exception: reported=$reported" }
        exit(if (reported) 0 else 1)
    }
    logger.v { "IPC crash reporter installed; starting pipe workers" }
    val pipeWriterDispatcher = newSingleThreadContext("plugin-pipe-writer")
    val pipeReaderDispatcher = newSingleThreadContext("plugin-pipe-reader")
    val senderSubscribed = CompletableDeferred<Unit>()
    val sender = launch(pipeWriterDispatcher, start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter sender coroutine" }
        EventBus.subscribe<MilkyConsoleFromEvent.FromPlugin> { senderSubscribed.complete(Unit) }.collect { event ->
            logger.v { "sender received outbound event: type=${event::class.simpleName}" }
            writeEvent(event)
            if (event is PluginHandshakeResult) {
                logger.d { "terminal handshake result written: ${event::class.simpleName}" }
                terminalResultWritten.complete(Unit)
            } else {
                logger.v { "non-terminal plugin event written: ${event::class.simpleName}" }
            }
        }
        logger.v { "exit sender coroutine" }
    }
    senderSubscribed.await()
    logger.d { "sender subscription ready: expected=true" }

    Logger.setLogWriters(object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            // The transport's codec and pipe operations are instrumented with Kermit.
            // Keep those implementation details local so writing a log cannot log itself.
            if (tag in logTransportTags) return
            val pluginLogger = Logger.withTag(tag)
            writeLog(PluginLog(severity.ordinal, pluginLogger.tag, message, throwable?.stackTraceToString()))
        }
    })
    logger.d { "logger bridge installed: forwards plugin logs through dedicated pipe" }

    // This is the readiness condition represented by PluginHandshakeRequest.
    val hostHandshakeRequest = async(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter host handshake request waiter" }
        EventBus.subscribe<HostHandshakeRequest>().first()
    }

    val receiver = launch(pipeReaderDispatcher) {
        logger.v { "enter receiver coroutine" }
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        logger.d { "split packet cache initialized: ttl=1m, maxBytes=${16 * 1024 * 1024}" }
        while (isActive) {
            logger.v { "receiver waiting for next host packet" }
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
                logger.d { "merged host split packet: group=$group, parts=${ordered.size}, bytes=${ordered.sumOf { it.data.size }}" }
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
        logger.v { "exit receiver coroutine: active=$isActive" }
    }

    suspend fun reject(message: String, error: PluginHandshakeError? = null): Nothing {
        logger.e { "rejecting loader flow: message=$message, error=$error" }
        EventBus.post(PluginHandshakeResult.Rejected(message, error))
        terminalResultWritten.await()
        logger.v { "terminal rejection written; closing loader resources" }
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
    logger.d { "host handshake request received within timeout: expected=true" }

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
    logger.d { "plugin API struct received: abi=${api.abi_version}, size=${api.struct_size}" }

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
    logger.v { "allocated host API struct: bytes=${sizeOf<milky_console_host_api>()}" }
    PendingPluginApiRequests.initialize { request ->
        logger.v { "forwarding plugin API request to host: tag=${request.tag}" }
        EventBus.postBlocking(request)
    }

    hostApi.pointed.abi_version = MILKY_CONSOLE_HOST_ABI_VERSION
    hostApi.pointed.struct_size = sizeOf<milky_console_host_api>().toUInt()
    hostApi.pointed.send_message = staticCFunction { uin, type, message ->
        val type = type?.toKString() ?: return@staticCFunction cValue {
            logger.w { "send_message received null type; returning INVALID_ARGUMENT" }
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }
        val text = message?.toKString() ?: return@staticCFunction cValue {
            logger.w { "send_message received null message; returning INVALID_ARGUMENT" }
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }
        logger.v { "enter send_message: uin=$uin, type=$type, bytes=${text.encodeToByteArray().size}" }

        val event = try {
            text.toPluginApiRequest(type)!!.copy(uin = uin)
        } catch (_: Throwable) {
            logger.w { "send_message payload could not be decoded; returning INVALID_ARGUMENT" }
            return@staticCFunction cValue {
                uuid[0] = 0
                result = MILKY_RESULT_INVALID_ARGUMENT
            }
        }

        if (!PendingPluginApiRequests.register(event)) {
            logger.e { "send_message request registration failed: tag=${event.tag}" }
            return@staticCFunction cValue {
                uuid[0] = 0
                result = MILKY_RESULT_BUFFER_OVERFLOW
            }
        }
        logger.d { "send_message registered request: tag=${event.tag}, expected=true" }
        return@staticCFunction cValue {
            event.tag.toString().encodeToByteArray().usePinned { bytes ->
                memcpy(
                    uuid,
                    bytes.addressOf(0),
                    bytes.get().size.convert()
                )
                uuid[bytes.get().size] = 0
            }
            logger.v { "exit send_message successfully: tag=${event.tag}" }
        }
    }
    hostApi.pointed.wait_message_result = staticCFunction { id, timeout, buffer, size ->
        logger.v { "enter wait_message_result: timeoutMs=$timeout, bufferSize=$size" }
        if (id == null || timeout <= 0 || size <= 0u || buffer == null || id[36] != 0.toByte()) {
            logger.w { "wait_message_result received invalid arguments" }
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }
        val uuid = try {
            Uuid.parse(id.toKString())
        } catch (_: IllegalArgumentException) {
            logger.w { "wait_message_result received invalid UUID" }
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }

        val deferred = PendingPluginApiRequests.get(uuid) ?: return@staticCFunction cValue {
            logger.w { "wait_message_result has no pending request: tag=$uuid" }
            result = MILKY_RESULT_INVALID_ARGUMENT
            required_size = 0u
        }
        logger.d { "wait_message_result found pending request: tag=$uuid, expected=true" }

        val result = runBlocking {
            withTimeoutOrNull(timeout.milliseconds) {
                deferred.await()
            }
        }

        if (result == null) {
            logger.w { "wait_message_result timed out: tag=$uuid, timeoutMs=$timeout" }
            return@staticCFunction cValue {
                this.result = MILKY_RESULT_TIMEOUT
                required_size = 0u
            }
        }

        milkyJsonModule.encodeToString(result.payload).encodeToByteArray().usePinned {
            val byteCount = it.get().size
            if (size < byteCount.toUInt() + 1u) {
                logger.w { "wait_message_result buffer too short: tag=$uuid, capacity=$size, required=${byteCount + 1}" }
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
                logger.d { "wait_message_result copied response: tag=$uuid, bytes=$byteCount, expected=true" }
                logger.v { "exit wait_message_result successfully: tag=$uuid" }
                this.result = MILKY_RESULT_OK
                required_size = byteCount.toULong() + 1u
            }
        }
    }

    val callbackDispatcher = newSingleThreadContext("plugin-callback")
    logger.d { "dispatching on_load" }
    val loaded = try {
        withContext(callbackDispatcher) {
            memScoped {
                api.on_load!!.invoke(config.cstr.getPointer(this), hostApi)
            }
        }
    } catch (e: Throwable) {
        logger.a { "plugin on_load crashed loader boundary: ${e.stackTraceToString()}" }
        callbackDispatcher.close()
        free(hostApi)
        reject("插件初始化崩溃: ${e.message}", PluginHandshakeError.INITIALIZATION_FAILED)
    }
    logger.d { "on_load completed with result=$loaded" }
    logger.i { "on_load execution result: loaded=$loaded, pendingSendFailure=${PendingPluginApiRequests.lastSendFailure() != null}" }
    PendingPluginApiRequests.lastSendFailure()?.let {
        logger.e { "send_message failed:\n$it" }
    }
    if (loaded == MILKY_FALSE) {
        logger.e { "plugin on_load returned MILKY_FALSE" }
        callbackDispatcher.close()
        free(hostApi)
        reject("插件初始化失败", PluginHandshakeError.INITIALIZATION_FAILED)
    }

    // Ready is sent only after all runtime listeners are registered.
    val hostEvents = launch(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter hostEvents coroutine" }
        EventBus.subscribe<HostEvent>().collect { message ->
            logger.d { "enter on_message callback: type=${message.event::class.simpleName}" }
            try {
                withContext(callbackDispatcher) {
                    memScoped {
                        api.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
                    }
                }
            } catch (e: Throwable) {
                logger.a { "plugin on_message crashed loader boundary: ${e.stackTraceToString()}" }
                throw e
            }
            logger.i { "exit on_message callback successfully: type=${message.event::class.simpleName}" }
        }
        logger.v { "exit hostEvents coroutine" }
    }
    val hostClose = async(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter host close waiter" }
        EventBus.subscribe<HostClose>().first()
    }
    EventBus.post(PluginHandshakeResult.Ready)
    logger.i { "sent Ready; waiting for HostClose" }
    terminalResultWritten.await()

    hostClose.await()
    logger.d { "HostClose received: expected=true" }
    hostEvents.cancelAndJoin()
    logger.d { "host event callback collector cancelled: expected=true" }
    logger.i { "enter on_unload callback" }
    val exitCode = try {
        withContext(callbackDispatcher) { api.on_unload?.invoke() ?: 0 }
    } catch (e: Throwable) {
        logger.a { "plugin on_unload crashed loader boundary: ${e.stackTraceToString()}" }
        1
    }
    // `exit` is intentional: a blocked pipe reader is a child of runBlocking and
    // would otherwise keep the loader alive after a successful unload. The OS
    // releases the pipes and loader resources; the exit code is the plugin's
    // on_unload result and is observed by the host lifecycle.
    logger.i { "exit main successfully: plugin exitCode=$exitCode" }
    exit(exitCode)
}
