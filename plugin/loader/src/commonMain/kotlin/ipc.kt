import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import platform.posix.exit
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginApiResponse
import top.kagg886.milky.console.protocol.PluginClosed
import top.kagg886.milky.console.protocol.PluginHandshakeResult
import top.kagg886.milky.console.protocol.PluginLog
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.readContent
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = Logger.withTag("PluginLoader")
private val logTransportTags = setOf("ProtocolJson", "ProtocolPacket", "ProtocolPacketCodec", "UnixPipe", "WindowsPipe")

/**
 * 崩溃上报器：这是 Kotlin 异常逃到 Kotlin/Native 边界导致运行时 SIGABRT 前的最后手段。
 * 直接写管道而不是走 EventBus：失败的很可能就是 EventBus 的 worker，
 * 而 writeEvent 是同步的、由 pipeWriteLock 串行化。
 */
@OptIn(ExperimentalNativeApi::class)
internal fun installCrashReporter() {
    setUnhandledExceptionHook { throwable ->
        val message = "loader 未处理的 Kotlin 异常: ${throwable.message ?: throwable::class.simpleName}"
        logger.a { "loader crashed after IPC crash reporter was ready: ${throwable.stackTraceToString()}" }
        val reported = runCatching {
            val stacktrace = throwable.stackTraceToString()
            LoaderApplication.writeLog(PluginLog(Severity.Error.ordinal, "PluginLoader", message, stacktrace))
            LoaderApplication.writeEvent(PluginClosed(message, stacktrace))
        }.isSuccess
        // The loader cannot safely resume after an exception has escaped a
        // top-level or worker boundary. A successfully written PluginClosed is
        // a protocol-complete shutdown, so it exits with code 0; otherwise the
        // host did not receive the report and must treat it as a failure.
        logger.e { "exit main after unhandled exception: reported=$reported" }
        exit(if (reported) 0 else 1)
    }
    logger.v { "IPC crash reporter installed; starting pipe workers" }
}

/** 启动 sender（EventBus → 管道）协程并等待其订阅就绪。 */
internal suspend fun CoroutineScope.startSender() {
    val senderSubscribed = CompletableDeferred<Unit>()
    LoaderApplication.senderJob = launch(LoaderApplication.pipeWriterDispatcher, start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter sender coroutine" }
        EventBus.subscribe<MilkyConsoleFromEvent.FromPlugin> { senderSubscribed.complete(Unit) }.collect { event ->
            logger.v { "sender received outbound event: type=${event::class.simpleName}" }
            LoaderApplication.writeEvent(event)
            if (event is PluginHandshakeResult) {
                logger.d { "terminal handshake result written: ${event::class.simpleName}" }
                LoaderApplication.terminalResultWritten.complete(Unit)
            } else {
                logger.v { "non-terminal plugin event written: type=${event::class.simpleName}" }
            }
        }
        logger.v { "exit sender coroutine" }
    }
    senderSubscribed.await()
    logger.d { "sender subscription ready: expected=true" }
}

/** 启动 receiver（管道 → 分片重组 → EventBus / PendingPluginApiRequests）协程。 */
@OptIn(ExperimentalSerializationApi::class)
internal fun CoroutineScope.startReceiver() {
    LoaderApplication.receiverJob = launch(LoaderApplication.pipeReaderDispatcher) {
        logger.v { "enter receiver coroutine" }
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        logger.d { "split packet cache initialized: ttl=1m, maxBytes=${16 * 1024 * 1024}" }
        while (isActive) {
            logger.v { "receiver waiting for next host packet" }
            val packet = LoaderApplication.source.readPacket()
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
}

/** 把 Kermit 日志桥接到专用日志管道；传输层自身的日志保留在本地，避免写日志时递归记录。 */
internal fun installLogBridge() {
    Logger.setLogWriters(object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            // The transport's codec and pipe operations are instrumented with Kermit.
            // Keep those implementation details local so writing a log cannot log itself.
            if (tag in logTransportTags) return
            val pluginLogger = Logger.withTag(tag)
            LoaderApplication.writeLog(PluginLog(severity.ordinal, pluginLogger.tag, message, throwable?.stackTraceToString()))
        }
    })
    logger.d { "logger bridge installed: forwards plugin logs through dedicated pipe" }
}
