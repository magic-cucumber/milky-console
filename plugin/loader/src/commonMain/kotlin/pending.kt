import kotlinx.coroutines.CompletableDeferred
import co.touchlab.kermit.Logger
import top.kagg886.milky.console.protocol.PluginApiRequest
import top.kagg886.milky.console.protocol.PluginApiResponse
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid

private val logger = Logger.withTag("PendingApi")

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
        logger.i { "enter initialize" }
        this.sendRequest = sendRequest
        logger.d { "send request bridge initialized: expected=true" }
        logger.i { "exit initialize successfully" }
    }

    fun register(request: PluginApiRequest): Boolean {
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
        logger.v { "sending pending request to host: tag=${request.tag}" }
        val sent = runCatching { sendRequest(request) }
            .onFailure {
                logger.e { "send request bridge threw: tag=${request.tag}, message=${it.message}" }
                sendFailure.store(it.stackTraceToString())
            }
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
        logger.d { "enter complete: tag=${response.tag}" }
        val deferred = get(response.tag)
        if (deferred == null) {
            logger.w { "complete received without pending request: tag=${response.tag}" }
        }
        deferred?.complete(response)
        logger.i { "exit complete: tag=${response.tag}, matched=${deferred != null}" }
    }

    fun get(tag: Uuid): CompletableDeferred<PluginApiResponse>? {
        logger.v { "enter get: tag=$tag" }
        val result = pending.load().firstOrNull { it.tag == tag }?.response
        logger.d { "get pending result: tag=$tag, found=${result != null}, expected=${result != null}" }
        logger.v { "exit get: tag=$tag" }
        return result
    }

    fun lastSendFailure(): String? {
        logger.v { "enter lastSendFailure" }
        val result = sendFailure.load()
        logger.d { "last send failure loaded: present=${result != null}" }
        logger.v { "exit lastSendFailure" }
        return result
    }

    fun remove(tag: Uuid) {
        logger.d { "enter remove: tag=$tag" }
        while (true) {
            val current = pending.load()
            val updated = current.filterNot { it.tag == tag }
            if (updated.size == current.size) {
                logger.v { "remove skipped: tag not pending=$tag" }
                return
            }
            if (pending.compareAndSet(current, updated)) {
                logger.i { "exit remove successfully: tag=$tag, pending=${updated.size}" }
                return
            } else {
                logger.v { "remove retried after concurrent update: tag=$tag" }
            }
        }
    }
}
