import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.kagg886.milky.console.protocol.PluginApiRequest
import top.kagg886.milky.console.protocol.PluginApiResponse
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/17 15:04
 * ================================================
 */

object PendingPluginApiRequests {
    private val cache = LRUCache.create<Uuid, CompletableDeferred<PluginApiResponse>>(1.minutes, maxSize = 100) { _, _ -> 1 }
    private lateinit var scope: CoroutineScope

    fun initialize(
        scope: CoroutineScope,
    ) {
        this.scope = scope
    }

    fun register(request: PluginApiRequest) {
        scope.launch {
            cache.put(request.tag, CompletableDeferred())
            EventBus.post(request)
        }
    }

    fun complete(response: PluginApiResponse) {
        cache.getIfAvailable(response.tag)?.complete(response)
    }

    fun get(tag: Uuid): CompletableDeferred<PluginApiResponse>? = cache.getIfAvailable(tag)

}
