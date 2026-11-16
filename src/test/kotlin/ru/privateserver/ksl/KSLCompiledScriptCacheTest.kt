package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KSLCompiledScriptCacheTest {
    @Test
    fun hitsAndMissesAreCounted() {
        val cache = KSLCompiledScriptCache<String>(2)
        val key = KSLCompileCacheKey("one", "source-a")
        assertNull(cache.get(key))
        cache.put(key, "compiled")
        assertEquals("compiled", cache.get(key))
        val stats = cache.stats()
        assertEquals(1L, stats.hits)
        assertEquals(1L, stats.misses)
        assertEquals(0.5, stats.hitRate)
    }

    @Test
    fun newerFingerprintReplacesOldEntryForSameScript() {
        val cache = KSLCompiledScriptCache<String>(3)
        cache.put(KSLCompileCacheKey("one", "old"), "old")
        cache.put(KSLCompileCacheKey("one", "new"), "new")
        assertNull(cache.get(KSLCompileCacheKey("one", "old")))
        assertEquals("new", cache.get(KSLCompileCacheKey("one", "new")))
        assertEquals(1, cache.stats().size)
    }

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val cache = KSLCompiledScriptCache<String>(2)
        val first = KSLCompileCacheKey("one", "a")
        val second = KSLCompileCacheKey("two", "b")
        val third = KSLCompileCacheKey("three", "c")
        cache.put(first, "1")
        cache.put(second, "2")
        cache.get(first)
        cache.put(third, "3")
        assertEquals("1", cache.get(first))
        assertNull(cache.get(second))
        assertEquals(1L, cache.stats().evictions)
    }

    @Test
    fun invalidationAndClearReturnRemovedCounts() {
        val cache = KSLCompiledScriptCache<String>(3)
        cache.put(KSLCompileCacheKey("one", "a"), "1")
        cache.put(KSLCompileCacheKey("two", "b"), "2")
        assertEquals(1, cache.invalidateScript("one"))
        assertEquals(1, cache.clear())
        assertEquals(0, cache.stats().size)
    }
}
