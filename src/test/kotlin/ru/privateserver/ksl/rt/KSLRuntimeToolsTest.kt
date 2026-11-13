package ru.privateserver.ksl.rt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class KSLRuntimeToolsTest {
    @Test fun cacheEvictsLeastRecentlyUsedEntry() {
        val cache = KSLCache<String, Int>(2)
        cache.put("a", 1); cache.put("b", 2)
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)
        assertEquals(1, cache.get("a")); assertNull(cache.get("b"))
        assertEquals(3, cache.get("c")); assertEquals(1L, cache.stats().evictions)
    }
    @Test fun cacheGetOrPutReusesValues() {
        val cache = KSLCache<String, Int>(4)
        var calls = 0
        assertEquals(42, cache.getOrPut("answer") { calls++; 42 })
        assertEquals(42, cache.getOrPut("answer") { calls++; 0 })
        assertEquals(1, calls)
    }
    @Test fun cacheValidatesLimits() {
        assertThrows(IllegalArgumentException::class.java) { KSLCache<String, Int>(0) }
        assertThrows(IllegalArgumentException::class.java) { KSLCache<String, Int>(1, Duration.ofNanos(-1)) }
    }
    @Test fun profilerRecordsFailuresAndResets() {
        val profiler = KSLProfiler("test")
        assertEquals("ok", profiler.measure { "ok" })
        assertThrows(IllegalStateException::class.java) { profiler.measure<String> { error("expected") } }
        assertEquals(2L, profiler.snapshot().calls)
        assertEquals(1L, profiler.snapshot().failures)
        profiler.reset()
        assertEquals(0L, profiler.snapshot().calls)
    }
}
