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
    @Test fun cacheExpiresEntriesAfterConfiguredDuration() {
        val cache = KSLCache<String, Int>(2, Duration.ofMillis(10))
        cache.put("short-lived", 1)
        Thread.sleep(30)
        assertNull(cache.get("short-lived"))
    }
    @Test fun cacheSupportsExpireAfterAccess() {
        val cache = KSLCache<String, Int>(2, expireAfterAccess = Duration.ofMillis(80))
        cache.put("active", 1)
        Thread.sleep(45)
        assertEquals(1, cache["active"])
        Thread.sleep(45)
        assertEquals(1, cache["active"])
        Thread.sleep(100)
        assertNull(cache["active"])
    }

    @Test fun cacheSupportsMapStyleHelpersAndCleanup() {
        val cache = KSLCache<String, Int>(3)
        assertNull(cache.putIfAbsent("a", 1))
        assertEquals(1, cache.putIfAbsent("a", 2))
        assertEquals(1, cache.getOrDefault("a", 9))
        assertEquals(2, cache.computeIfAbsent("b") { 2 })
        cache.putAll(mapOf("c" to 3))
        assertEquals(setOf("a", "b", "c"), cache.keysSnapshot().toSet())
        assertTrue(cache.invalidate("b"))
        assertEquals(0, cache.cleanUp())
        assertEquals(2, cache.currentSize)
    }

    @Test fun cacheRejectsNegativeAccessTtl() {
        assertThrows(IllegalArgumentException::class.java) { KSLCache<String, Int>(1, expireAfterAccess = Duration.ofNanos(-1)) }
    }

    @Test fun profilerRegistryRejectsUnboundedNamesAndAllowsCleanup() {
        val created = mutableListOf<String>()
        val prefix = "capacity-test-${System.nanoTime()}-"
        try {
            while (KSLRT.profilerNames().size < KSLRT.MAX_PROFILERS) {
                val name = prefix + created.size
                KSLRT.profiler(name)
                created += name
            }
            assertThrows(IllegalStateException::class.java) {
                KSLRT.profiler(prefix + "overflow")
            }
        } finally {
            created.forEach { KSLRT.removeProfiler(it) }
        }
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
