import kotlinx.coroutines.CompletableDeferred
import co.touchlab.kermit.Logger
import top.kagg886.milky.console.protocol.PluginApiRequest
import top.kagg886.milky.console.protocol.PluginApiResponse
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid

private val pendingApiLogger = Logger.withTag("PendingApi")

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/17 15:04
 * ================================================
 */

@OptIn(ExperimentalAtomicApi::class)
object PendingPluginApiRequests {
    private const val MAX_SIZE = 100

    private data class Pending(
        val tag: Uuid,
        val response: CompletableDeferred<PluginApiResponse>,
    )

    private val pending = AtomicReference<List<Pending>>(emptyList())
    private val sendFailure = AtomicReference<String?>(null)
    private lateinit var sendRequest: (PluginApiRequest) -> Boolean

    fun initialize(sendRequest: (PluginApiRequest) -> Boolean) {
        pendingApiLogger.i { "enter initialize" }
        this.sendRequest = sendRequest
        pendingApiLogger.i { "exit initialize successfully" }
    }

    fun register(request: PluginApiRequest): Boolean {
        val logger = pendingApiLogger
        logger.i { "enter register: tag=${request.tag}, pending=${pending.load().size}" }
        // wait_message_result may be called immediately after send_message returns,
        // including from a synchronous native lifecycle callback. Register the
        // deferred and enqueue the request without suspending that callback.
        val entry = Pending(request.tag, CompletableDeferred())
        while (true) {
            val current = pending.load()
            val updated = (current.filterNot { it.tag == request.tag } + entry).takeLast(MAX_SIZE)
            if (pending.compareAndSet(current, updated)) {
                logger.d { "registered pending request: tag=${request.tag}, size=${updated.size}" }
                break
            } else {
                logger.v { "pending request registration retried after concurrent update: tag=${request.tag}" }
            }
        }
        val sent = runCatching { sendRequest(request) }
            .onFailure { sendFailure.store(it.stackTraceToString()) }
            .getOrDefault(false)
        if (!sent) {
            logger.e { "send request failed; removing pending entry: tag=${request.tag}" }
            remove(request.tag)
            return false
        }
        logger.i { "exit register successfully: tag=${request.tag}, pending=${pending.load().size}" }
        return true
    }

    fun complete(response: PluginApiResponse) {
        pendingApiLogger.d { "enter complete: tag=${response.tag}" }
        val deferred = get(response.tag)
        deferred?.complete(response)
        pendingApiLogger.i { "exit complete: tag=${response.tag}, matched=${deferred != null}" }
    }

    fun get(tag: Uuid): CompletableDeferred<PluginApiResponse>? =
        pending.load().firstOrNull { it.tag == tag }?.response

    fun lastSendFailure(): String? = sendFailure.load()

    fun remove(tag: Uuid) {
        pendingApiLogger.d { "enter remove: tag=$tag" }
        while (true) {
            val current = pending.load()
            val updated = current.filterNot { it.tag == tag }
            if (updated.size == current.size) {
                pendingApiLogger.v { "remove skipped: tag not pending=$tag" }
                return
            }
            if (pending.compareAndSet(current, updated)) {
                pendingApiLogger.i { "exit remove successfully: tag=$tag" }
                return
            }
        }
    }
}
