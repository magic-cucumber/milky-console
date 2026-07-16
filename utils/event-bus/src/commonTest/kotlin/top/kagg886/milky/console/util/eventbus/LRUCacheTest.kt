package top.kagg886.milky.console.util.eventbus

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LRUCacheTest {
    @Test
    fun byteArraysConsumeTheirActualByteSizeAndEvictLeastRecentlyUsedEntry() = runBlocking {
        val cache = LRUCache.create<String>(time = 1_000.milliseconds, maxSize = 3)

        cache.put("first", byteArrayOf(1, 2))
        cache.put("second", byteArrayOf(3))
        cache.get("first")
        cache.put("third", byteArrayOf(4))

        assertEquals(setOf("first", "third"), cache.getKeys())
        assertNull(cache.get("second"))
    }

    @Test
    fun cacheOperationsDelegateToTheUnderlyingCache() = runBlocking {
        val cache = LRUCache.create<String>(time = 1_000.milliseconds)
        val value = byteArrayOf(1, 2)

        cache.put("key", value)

        assertEquals(2, cache.size)
        assertEquals(setOf("key"), cache.getKeys())
        assertEquals(value.toList(), cache.get("key")?.toList())
        assertEquals(value.toList(), cache.getOrDefault("key", byteArrayOf()).toList())
        assertNull(cache.getIfAvailable("missing"))
        assertEquals(value.toList(), cache.getIfAvailableOrDefault("key", byteArrayOf()).toList())
    }

    @Test
    fun entriesExpireAfterTheirWriteDuration() = runBlocking {
        val cache = LRUCache.create<String>(time = 1.milliseconds)
        cache.put("key", byteArrayOf(1))

        delay(20)

        assertNull(cache.get("key"))
        assertTrue(cache.getKeys().isEmpty())
    }
}
