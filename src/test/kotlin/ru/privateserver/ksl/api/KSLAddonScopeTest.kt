package ru.privateserver.ksl.api

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class KSLAddonScopeTest {
    private fun scope(): KSLAddonScope {
        val plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, _, _ -> null } as Plugin
        return KSLAddonScope(plugin) { _, _ -> AutoCloseable { } }
    }

    @Test
    fun closesResourcesInReverseOrderAndOnlyOnce() {
        val scope = scope()
        val closed = mutableListOf<String>()

        scope.own(AutoCloseable { closed += "first" })
        scope.own(AutoCloseable { closed += "second" })
        assertEquals(2, scope.resourceCount)

        scope.close()
        scope.close()

        assertEquals(listOf("second", "first"), closed)
        assertEquals(0, scope.resourceCount)
        assertTrue(scope.isClosed)
    }

    @Test
    fun releaseClosesOnlyTheRequestedResource() {
        val scope = scope()
        var firstClosed = false
        var secondClosed = false
        val first = AutoCloseable { firstClosed = true }
        val second = AutoCloseable { secondClosed = true }

        scope.own(first)
        scope.own(second)

        assertTrue(scope.release(first))
        assertFalse(scope.release(first))
        assertTrue(firstClosed)
        assertFalse(secondClosed)

        scope.close()
        assertTrue(secondClosed)
    }

    @Test
    fun owningAfterCloseImmediatelyClosesAndRejectsResource() {
        val scope = scope()
        scope.close()
        var released = false

        assertThrows(IllegalStateException::class.java) {
            scope.own(AutoCloseable { released = true })
        }

        assertTrue(released)
        assertTrue(scope.isClosed)
    }

    @Test
    fun closeAttemptsRemainingResourcesAfterOneFails() {
        val scope = scope()
        var secondClosed = false
        scope.own(AutoCloseable { secondClosed = true })
        scope.own(AutoCloseable { error("close failed") })

        assertThrows(IllegalStateException::class.java) { scope.close() }
        assertTrue(secondClosed)
        assertTrue(scope.isClosed)
    }
}
