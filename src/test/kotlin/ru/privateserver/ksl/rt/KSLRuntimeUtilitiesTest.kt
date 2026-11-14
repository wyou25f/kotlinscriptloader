package ru.privateserver.ksl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLRuntimeUtilitiesTest {
    @Test
    fun `cache supports operator and descriptive aliases`() {
        val cache = KSLRT.lru<String, Int>(2)
        cache["one"] = 1
        assertEquals(1, cache["one"])
        assertTrue(cache.has("one"))
        assertEquals(2, cache.getOrCompute("two") { 2 })
        assertTrue(cache.remove("one"))
        assertFalse(cache.containsKey("one"))
        assertNull(cache["one"])
        assertEquals(listOf("two"), cache.keysSnapshot())
        assertEquals(listOf(2), cache.valuesSnapshot())
        assertTrue(cache.stats().requests >= 2)
        cache.invalidateAll()
        assertEquals(0, cache.currentSize)
    }

    @Test
    fun `runtime cache factories expose access expiration`() {
        val direct = KSLRT.cache<String, Int>(2, expireAfterAccess = java.time.Duration.ofMillis(20))
        val alias = kslrt.lru<String, Int>(2, expireAfterAccess = java.time.Duration.ofMillis(20))
        direct.put("a", 1)
        alias.put("b", 2)
        assertEquals(1, direct["a"])
        assertEquals(2, alias["b"])
    }

    @Test
    fun `cache reports hit rate and can reset counters`() {
        val cache = kslrt.cache<String, String>(4)
        cache.put("a", "value")
        cache.get("a")
        cache.get("missing")
        val stats = cache.stats()
        assertEquals(0.5, stats.hitRate)
        assertEquals(2L, stats.requests)
        cache.resetStats()
        assertEquals(0L, cache.stats().requests)
    }

    @Test
    fun `profiler exposes readable timing aliases`() {
        val profiler = KSLRT.profile("test")
        assertEquals("result", profiler.time { "result" })
        val snapshot = profiler.stats()
        assertEquals(1L, snapshot.calls)
        assertEquals(1L, snapshot.successfulCalls)
        assertTrue(snapshot.totalMillis >= 0.0)
        profiler.clear()
        assertEquals(0L, profiler.snapshot().calls)
    }

    @Test
    fun `membership checks do not refresh LRU recency`() {
        val cache = KSLRT.lru<String, Int>(2)
        cache.put("oldest", 1)
        cache.put("newest", 2)

        assertTrue(cache.has("oldest"))
        cache.put("third", 3)

        assertFalse(cache.has("oldest"))
        assertTrue(cache.has("newest"))
        assertTrue(cache.has("third"))
    }

    @Test
    fun `named runtime measurements remain available through the profiler registry`() {
        val name = "test.runtime.measure.registry"
        KSLRT.removeProfiler(name)

        assertEquals("measured", KSLRT.measure(name) { "measured" })
        val profiler = KSLRT.profiler(name)
        assertEquals(1L, profiler.snapshot().calls)
        assertTrue(name in KSLRT.profilerNames())
        assertTrue(KSLRT.removeProfiler(name))
        assertFalse(name in KSLRT.profilerNames())
    }

    @Test
    fun `legacy and short runtime factories point to the same utility types`() {
        assertTrue(rt === KSLRT)
        assertTrue(runtime === KSLRT)
        assertEquals(KSLCache::class, KSLRT.cache<String, Int>(2)::class)
    }
}
