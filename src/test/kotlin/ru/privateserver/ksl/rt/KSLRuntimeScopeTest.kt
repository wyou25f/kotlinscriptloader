package ru.privateserver.ksl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class KSLRuntimeScopeTest {
    @Test
    fun `scope closes resources in reverse order and only once`() {
        val scope = KSLRuntimeScope("test")
        val order = mutableListOf<Int>()
        scope.own(AutoCloseable { order += 1 })
        scope.onClose { order += 2 }
        scope.own(AutoCloseable { order += 3 })

        scope.close()
        scope.close()

        assertEquals(listOf(3, 2, 1), order)
        assertTrue(scope.isClosed)
        assertEquals(0, scope.ownedResourceCount)
    }

    @Test
    fun `scope owns caches and scoped profilers`() {
        val scope = KSLRuntimeScope("shop")
        val cache = scope.lru<String, Int>(4)
        cache["key"] = 42
        val profiler = scope.profile("load")
        assertEquals("ok", profiler { "ok" })
        assertEquals(setOf("load"), scope.profilerNames)
        assertEquals(1L, scope.profileSnapshots().single().calls)

        scope.close()
        assertTrue(cache.isEmpty)
        assertTrue(scope.profilerNames.isEmpty())
        assertTrue(scope.isClosed)
    }

    @Test
    fun `closing registration handle unregisters captured callback`() {
        val scope = KSLRuntimeScope("handles")
        val calls = AtomicInteger()
        val handle = scope.onClose { calls.incrementAndGet() }
        handle.close()
        assertEquals(0, scope.ownedResourceCount)
        scope.close()
        assertEquals(0, calls.get())
    }

    @Test
    fun `resources registered after close are closed immediately and rejected`() {
        val scope = KSLRuntimeScope("closed")
        scope.close()
        val calls = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            scope.own(AutoCloseable { calls.incrementAndGet() })
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `cleanup continues after a resource fails`() {
        val scope = KSLRuntimeScope("failure")
        val calls = AtomicInteger()
        scope.own(AutoCloseable { calls.incrementAndGet() })
        scope.onClose { throw IllegalStateException("broken") }
        scope.own(AutoCloseable { calls.incrementAndGet() })

        val failure = assertThrows(IllegalStateException::class.java) { scope.close() }
        assertTrue(failure.message!!.contains("failed to close"))
        assertEquals(2, calls.get())
        assertTrue(scope.isClosed)
    }

    @Test
    fun `scope bounds retained callbacks and scoped profiler names`() {
        val scope = KSLRuntimeScope("bounded")
        repeat(KSLRuntimeScope.MAX_OWNED_RESOURCES) { scope.onClose {} }
        assertThrows(IllegalStateException::class.java) { scope.onClose {} }
        scope.close()

        val profiles = KSLRuntimeScope("profiles")
        repeat(KSLRuntimeScope.MAX_PROFILERS_PER_SCOPE) { profiles.profiler("p-$it") }
        assertThrows(IllegalStateException::class.java) { profiles.profiler("overflow") }
        profiles.close()
    }
}
