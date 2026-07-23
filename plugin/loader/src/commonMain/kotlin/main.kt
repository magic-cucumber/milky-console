import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import top.kagg886.milky.console.protocol.PluginHandshakeResult
import top.kagg886.milky.console.util.eventbus.EventBus

private val logger = Logger.withTag("PluginLoader")

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    // 1. 全局状态：参数、IPC 管道、工作目录与调度器
    LoaderApplication.init(args)

    // 2. 崩溃上报与管道收发协程（sender -> 日志桥 -> receiver，保持原初始化顺序）
    installCrashReporter()
    startSender()
    installLogBridge()
    startReceiver()

    // 3. 握手：PluginHandshakeRequest -> HostHandshakeRequest（10s 超时）
    performHandshake()

    // 4. 加载插件动态库并校验 ABI
    val api = loadPlugin()

    // 5. 安装 host API 桥接并执行 on_load
    val hostApi = installHostApiBridge()
    runOnLoad(api, hostApi)

    // 6. 注册运行时监听器后发送 Ready
    val hostEvents = startHostEventDispatch(api)
    val hostClose = prepareHostCloseWaiter()
    // Ready is sent only after all runtime listeners are registered.
    EventBus.post(PluginHandshakeResult.Ready)
    logger.i { "sent Ready; waiting for HostClose" }
    LoaderApplication.terminalResultWritten.await()

    // 7. 等待 host 关闭信号，执行优雅停机（on_unload -> exit）
    hostClose.await()
    logger.d { "HostClose received: expected=true" }
    unloadPlugin(api, hostEvents)
}
