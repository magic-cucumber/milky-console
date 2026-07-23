import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import co.touchlab.kermit.Logger
import top.kagg886.milky.console.protocol.PluginApiRequest
import top.kagg886.milky.console.protocol.PluginApiResponse
import top.kagg886.milky.console.util.eventbus.LRUCache
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    /** 挂起的 API 请求超过该时长自动过期，等效于自动取消注册。 */
    private val REQUEST_TTL = 10.seconds
    private const val MAX_SIZE = 100L

    private val pending = LRUCache.create<Uuid, CompletableDeferred<PluginApiResponse>>(REQUEST_TTL, MAX_SIZE) { _, _ -> 1L }
    private val sendFailure = AtomicReference<String?>(null)
    private lateinit var sendRequest: (PluginApiRequest) -> Boolean

    fun initialize(sendRequest: (PluginApiRequest) -> Boolean) {
        logger.i { "enter initialize" }
        this.sendRequest = sendRequest
        logger.d { "send request bridge initialized: expected=true" }
        logger.i { "exit initialize successfully" }
    }

    fun register(request: PluginApiRequest): Boolean {
        logger.i { "enter register: tag=${request.tag}" }
        // wait_message_result may be called immediately after send_message returns,
        // including from a synchronous native lifecycle callback. Register the
        // deferred and enqueue the request without suspending that callback.
        runBlocking { pending.put(request.tag, CompletableDeferred()) }
        logger.d { "registered pending request: tag=${request.tag}" }
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
        logger.i { "exit register successfully: tag=${request.tag}" }
        return true
    }

    suspend fun complete(response: PluginApiResponse) {
        logger.d { "enter complete: tag=${response.tag}" }
        val deferred = pending.get(response.tag)
        if (deferred == null) {
            logger.w { "complete received without pending request: tag=${response.tag}" }
        }
        deferred?.complete(response)
        logger.i { "exit complete: tag=${response.tag}, matched=${deferred != null}" }
    }

    fun get(tag: Uuid): CompletableDeferred<PluginApiResponse>? {
        logger.v { "enter get: tag=$tag" }
        val result = pending.getIfAvailable(tag)
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
        val removed = runBlocking { pending.remove(tag) }
        if (removed == null) {
            logger.v { "remove skipped: tag not pending=$tag" }
        } else {
            logger.i { "exit remove successfully: tag=$tag" }
        }
    }
}
