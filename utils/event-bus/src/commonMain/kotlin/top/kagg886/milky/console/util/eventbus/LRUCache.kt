package top.kagg886.milky.console.util.eventbus

import com.mayakapps.kache.InMemoryKache
import com.mayakapps.kache.KacheKeys
import com.mayakapps.kache.KacheStrategy
import com.mayakapps.kache.ObjectKache
import com.mayakapps.kache.SizeCalculator
import kotlinx.coroutines.Deferred
import kotlin.time.Duration

/** A size-bounded, least-recently-used cache whose entries expire after being written. */
interface LRUCache<K : Any, V : Any> {
    val maxSize: Long

    val size: Long

    suspend fun getKeys(): Set<K>

    suspend fun getUnderCreationKeys(): Set<K>

    suspend fun getAllKeys(): KacheKeys<K>

    suspend fun getOrDefault(key: K, defaultValue: V): V

    suspend fun get(key: K): V?

    fun getIfAvailableOrDefault(key: K, defaultValue: V): V

    fun getIfAvailable(key: K): V?

    suspend fun getOrPut(key: K, creationFunction: suspend (key: K) -> V?): V?

    suspend fun put(key: K, creationFunction: suspend (key: K) -> V?): V?

    suspend fun putAsync(key: K, creationFunction: suspend (key: K) -> V?): Deferred<V?>

    suspend fun put(key: K, value: V): V?

    suspend fun putAll(from: Map<out K, V>)

    suspend fun remove(key: K): V?

    suspend fun clear()

    suspend fun evictAll()

    suspend fun removeAllUnderCreation()

    suspend fun resize(maxSize: Long)

    suspend fun trimToSize(size: Long)

    suspend fun evictExpired()

    companion object {
        private const val DEFAULT_MAX_SIZE = 5L * 1024 * 1024

        /** Creates a byte-array cache whose capacity is measured in bytes. */
        fun <K : Any> create(
            time: Duration,
            maxSize: Long = DEFAULT_MAX_SIZE,
        ): LRUCache<K, ByteArray> = create(time, maxSize) { _, value -> value.size.toLong() }

        /**
         * Creates a cache whose capacity is measured by [sizeCalculator].
         *
         * For arbitrary objects Kotlin Multiplatform cannot determine their in-memory footprint,
         * so callers must provide a calculation for the value's actual payload size.
         */
        fun <K : Any, V : Any> create(
            time: Duration,
            maxSize: Long = DEFAULT_MAX_SIZE,
            sizeCalculator: SizeCalculator<K, V>,
        ): LRUCache<K, V> {
            val cache = InMemoryKache(maxSize) {
                strategy = KacheStrategy.LRU
                this.sizeCalculator = sizeCalculator
                expireAfterWriteDuration = time
            }
            return object : LRUCache<K, V>, ObjectKache<K, V> by cache {}
        }
    }

    typealias SizeCalculator<K, V> = (key: K, value: V) -> Long
}
