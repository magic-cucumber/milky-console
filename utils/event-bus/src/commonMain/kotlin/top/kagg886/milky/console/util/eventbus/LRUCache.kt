package top.kagg886.milky.console.util.eventbus

import co.touchlab.kermit.Logger
import com.mayakapps.kache.InMemoryKache
import com.mayakapps.kache.KacheKeys
import com.mayakapps.kache.KacheStrategy
import com.mayakapps.kache.ObjectKache
import com.mayakapps.kache.SizeCalculator
import kotlinx.coroutines.Deferred
import kotlin.time.Duration

private val logger = Logger.withTag("LRUCache")

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
        ): LRUCache<K, ByteArray> {
            logger.v { "enter create byte array cache: ttl=$time, maxSize=$maxSize" }
            val cache = create(time, maxSize) { _, value ->
                val size = value.size.toLong()
                logger.d { "calculated byte array cache entry size: bytes=$size, expected=${size >= 0}" }
                size
            }
            logger.i { "created byte array LRU cache: ttl=$time, maxSize=$maxSize" }
            logger.v { "exit create byte array cache: maxSize=$maxSize" }
            return cache
        }

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
            logger.v { "enter create cache: ttl=$time, maxSize=$maxSize" }
            val cache = InMemoryKache(maxSize) {
                logger.v { "configuring in-memory cache: maxSize=$maxSize" }
                strategy = KacheStrategy.LRU
                this.sizeCalculator = sizeCalculator
                expireAfterWriteDuration = time
                logger.d { "configured in-memory cache: strategy=${KacheStrategy.LRU}, ttl=$time, expected=true" }
            }
            logger.i { "created LRU cache: ttl=$time, maxSize=$maxSize" }
            val result = object : LRUCache<K, V>, ObjectKache<K, V> by cache {}
            logger.d { "wrapped object cache as LRUCache: expected=true" }
            logger.v { "exit create cache: maxSize=$maxSize" }
            return result
        }
    }

    typealias SizeCalculator<K, V> = (key: K, value: V) -> Long
}
