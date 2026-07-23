import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.serialization.ExperimentalSerializationApi
import platform.milky_console_interop.milky_console_host_api
import platform.posix.chdir
import platform.posix.exit
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginLog
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSource
import top.kagg886.milky.console.util.pipe.fromSink
import top.kagg886.milky.console.util.pipe.fromSource
import top.kagg886.milky.console.util.protocol.toPacket
import top.kagg886.milky.console.util.protocol.writePacket
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

private val logger = Logger.withTag("PluginLoader")

/**
 * Loader 进程的全局状态：IPC 管道、写锁、协程调度器与原生插件句柄。
 * 各功能模块（ipc / handshake / bridge / lifecycle）通过它共享运行时上下文。
 */
@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalAtomicApi::class,
    ExperimentalNativeApi::class,
    ExperimentalCoroutinesApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalSerializationApi::class,
)
internal object LoaderApplication {
    lateinit var sink: IPCAnonymousPipeSink
    lateinit var source: IPCAnonymousPipeSource
    lateinit var logSink: IPCAnonymousPipeSink
    lateinit var libpath: String
    lateinit var config: String

    // send_message is synchronous: serialize direct callback writes with the
    // regular EventBus sender so returning means the request is already in the pipe.
    val terminalResultWritten = CompletableDeferred<Unit>()
    val pipeWriteLock = AtomicBoolean(false)
    val logPipeWriteLock = AtomicBoolean(false)

    lateinit var pipeWriterDispatcher: CloseableCoroutineDispatcher
    lateinit var pipeReaderDispatcher: CloseableCoroutineDispatcher
    lateinit var callbackDispatcher: CloseableCoroutineDispatcher

    var senderJob: Job? = null
    var receiverJob: Job? = null

    var hostApi: CPointer<milky_console_host_api>? = null

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
    fun init(args: Array<String>) {
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
        sink = IPCAnonymousPipe.fromSink(args[0].toULong())
        source = IPCAnonymousPipe.fromSource(args[1].toULong())
        logSink = IPCAnonymousPipe.fromSink(args[2].toULong())
        libpath = args[3]
        config = args[4] // "{}"
        val base = args[5]

        if (chdir(base) != 0) {
            logger.e { "working directory setup failed: base=$base" }
            exit(-1)
        }
        logger.d { "runtime context initialized: sink=${args[0]}, source=${args[1]}, logSink=${args[2]}, library=$libpath, base=$base" }
        logger.v { "creating business and log pipe write locks" }

        pipeWriterDispatcher = newSingleThreadContext("plugin-pipe-writer")
        pipeReaderDispatcher = newSingleThreadContext("plugin-pipe-reader")
    }

    fun writeEvent(event: MilkyConsoleFromEvent.FromPlugin) {
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

    fun writeLog(event: PluginLog) {
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

    /** 释放 reject 路径持有的 loader 资源；正常停机路径由 `exit` 交给 OS 回收。 */
    fun releaseResources() {
        receiverJob?.cancel()
        senderJob?.cancel()
        pipeReaderDispatcher.close()
        pipeWriterDispatcher.close()
        sink.close()
        source.close()
    }
}
