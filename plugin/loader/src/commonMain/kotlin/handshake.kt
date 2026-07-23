import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.exit
import top.kagg886.milky.console.protocol.HostHandshakeRequest
import top.kagg886.milky.console.protocol.PluginHandshakeError
import top.kagg886.milky.console.protocol.PluginHandshakeRequest
import top.kagg886.milky.console.protocol.PluginHandshakeResult
import top.kagg886.milky.console.util.eventbus.EventBus
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("PluginLoader")

/**
 * 与 host 完成握手：发送 PluginHandshakeRequest 并等待 HostHandshakeRequest，
 * 10 秒超时则拒绝并退出。调用前 sender 与 receiver 必须已就绪。
 */
internal suspend fun CoroutineScope.performHandshake() {
    // This is the readiness condition represented by PluginHandshakeRequest.
    val hostHandshakeRequest = async(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter host handshake request waiter" }
        EventBus.subscribe<HostHandshakeRequest>().first()
    }

    // Sender and HostHandshakeRequest listener are now both active.
    EventBus.post(PluginHandshakeRequest)
    logger.i { "sent PluginHandshakeRequest; waiting for host listener" }
    if (withTimeoutOrNull(10.seconds) { hostHandshakeRequest.await() } == null) {
        logger.e { "host handshake request timed out" }
        reject("10秒内没有收到握手包，取消握手", PluginHandshakeError.TIMEOUT)
    }
    logger.d { "host handshake request received within timeout: expected=true" }
}

/** 写入握手拒绝结果，等待其真正进入管道后释放资源并以失败码退出。 */
internal suspend fun reject(message: String, error: PluginHandshakeError? = null): Nothing {
    logger.e { "rejecting loader flow: message=$message, error=$error" }
    EventBus.post(PluginHandshakeResult.Rejected(message, error))
    LoaderApplication.terminalResultWritten.await()
    logger.v { "terminal rejection written; closing loader resources" }
    LoaderApplication.releaseResources()
    exit(1)
    logger.a { "unreachable after exit(1)" }
    error("unreachable")
}
