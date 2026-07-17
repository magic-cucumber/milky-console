import kotlinx.coroutines.CompletableDeferred
import top.kagg886.milky.console.protocol.PluginApiRequest
import top.kagg886.milky.console.protocol.PluginApiResponse
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid

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
        this.sendRequest = sendRequest
    }

    fun register(request: PluginApiRequest): Boolean {
        // wait_message_result may be called immediately after send_message returns,
        // including from a synchronous native lifecycle callback. Register the
        // deferred and enqueue the request without suspending that callback.
        val entry = Pending(request.tag, CompletableDeferred())
        while (true) {
            val current = pending.load()
            val updated = (current.filterNot { it.tag == request.tag } + entry).takeLast(MAX_SIZE)
            if (pending.compareAndSet(current, updated)) break
        }
        val sent = runCatching { sendRequest(request) }
            .onFailure { sendFailure.store(it.stackTraceToString()) }
            .getOrDefault(false)
        if (!sent) {
            remove(request.tag)
            return false
        }
        return true
    }

    fun complete(response: PluginApiResponse) {
        val deferred = get(response.tag)
        deferred?.complete(response)
    }

    fun get(tag: Uuid): CompletableDeferred<PluginApiResponse>? =
        pending.load().firstOrNull { it.tag == tag }?.response

    fun lastSendFailure(): String? = sendFailure.load()

    fun remove(tag: Uuid) {
        while (true) {
            val current = pending.load()
            val updated = current.filterNot { it.tag == tag }
            if (updated.size == current.size || pending.compareAndSet(current, updated)) return
        }
    }
}
