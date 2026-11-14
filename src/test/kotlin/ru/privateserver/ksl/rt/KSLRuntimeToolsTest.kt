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
        val cache = KSLCache<String, Int>(2, expireAfterAccess = Duration.ofMillis(300))
        cache.put("active", 1)
        Thread.sleep(150)
        assertEquals(1, cache["active"])
        Thread.sleep(45)
        assertEquals(1, cache["active"])
        Thread.sleep(350)
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

        val expiring = KSLCache<String, Int>(2, expireAfter = Duration.ofMillis(10))
        expiring.put("old", 1)
        Thread.sleep(25)
        assertEquals(1, expiring.cleanUp())
        assertEquals(0, expiring.currentSize)
    }

    @Test fun cacheAtomicComputeSerializesConcurrentMisses() {
        val cache = KSLCache<String, Int>(8)
        val start = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 8).map {
                pool.submit<Int> {
                    start.await()
                    cache.computeIfAbsent("shared") {
                        Thread.sleep(20)
                        calls.incrementAndGet()
                    }
                }
            }
            start.countDown()
            assertEquals(List(8) { 1 }, results.map { it.get(2, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(1, calls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun cacheSupportsComputeReplaceRefreshAndSnapshots() {
        val cache = KSLCache<String, Int>(4, expireAfter = Duration.ofMillis(500))
        cache["a"] = 1
        assertEquals(2, cache.compute("a") { _, current -> (current ?: 0) + 1 })
        assertTrue(cache.replace("a", 3))
        assertEquals(3, cache.getAndPut("a", 4))
        assertEquals(mapOf("a" to 4), cache.entriesSnapshot())
        assertTrue(cache.refresh("a"))
        assertEquals(4, cache.getAndRemove("a"))
        assertTrue(cache.isEmpty)
        cache.putAll(mapOf("b" to 2, "c" to 3))
        assertTrue(cache.containsValue(3))
        assertEquals(1, cache.removeIf { key, _ -> key == "b" })
        assertEquals(setOf("c"), cache.keysSnapshot().toSet())
    }

    @Test fun cacheAtomicComputeCanCreateAndRemove() {
        val cache = KSLCache<String, Int>(2)
        assertEquals(7, cache.compute("a") { _, old -> (old ?: 0) + 7 })
        assertNull(cache.compute("a") { _, _ -> null })
        assertFalse(cache.containsKey("a"))
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
        profiler.recordMillis(2, failed = true)
        assertEquals(2_000_000L, profiler.snapshot().totalNanos)
        assertEquals(1L, profiler.snapshot().failures)
        assertEquals("timed", profiler { "timed" })
        assertEquals(2L, profiler.snapshot().calls)
        assertTrue(profiler.measureResult { error("captured") }.isFailure)
        assertEquals(3L, profiler.snapshot().calls)
    }
}
