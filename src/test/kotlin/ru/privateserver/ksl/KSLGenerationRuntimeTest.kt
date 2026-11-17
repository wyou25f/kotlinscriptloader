package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.rt.KSLRuntimeScope
import java.util.concurrent.atomic.AtomicBoolean

class KSLGenerationRuntimeTest {

    private fun manager() = KSLGenerationManager<String> { name -> KSLRuntimeScope(name) }

    @Test
    fun `candidate scope is visible only while its generation is evaluating`() {
        val manager = manager()
        val candidate = manager.begin("economy/shop", "stamp-1", "compiled-v1")

        assertNull(manager.currentScope("economy/shop"))
        manager.withCandidate(candidate) {
            assertSame(candidate.scope, manager.currentScope("economy/shop"))
            assertEquals(KSLGenerationState.INITIALIZING, candidate.state)
        }

        assertNull(manager.currentScope("economy/shop"))
        manager.fail(candidate)
        assertTrue(candidate.scope.isClosed)
    }

    @Test
    fun `activating a generation closes the previously active scope`() {
        val manager = manager()
        val first = manager.begin("economy/shop", "stamp-1", "compiled-v1")
        manager.withCandidate(first) { }
        manager.activate(first)

        val released = AtomicBoolean(false)
        first.scope.own(AutoCloseable { released.set(true) })

        val second = manager.begin("economy/shop", "stamp-2", "compiled-v2")
        manager.withCandidate(second) { }
        manager.activate(second)

        assertSame(second, manager.activeGeneration("economy/shop"))
        assertEquals(KSLGenerationState.ACTIVE, second.state)
        assertEquals(KSLGenerationState.DISPOSED, first.state)
        assertTrue(first.scope.isClosed)
        assertTrue(released.get())
        assertEquals(2L, second.number)
    }

    @Test
    fun `failed candidate leaves the active generation untouched`() {
        val manager = manager()
        val active = manager.begin("events/join", "good", "compiled-good")
        manager.withCandidate(active) { }
        manager.activate(active)

        val candidate = manager.begin("events/join", "bad", "compiled-bad")
        manager.withCandidate(candidate) { }
        manager.fail(candidate, IllegalStateException("startup failed"))

        assertSame(active, manager.activeGeneration("events/join"))
        assertEquals(KSLGenerationState.ACTIVE, active.state)
        assertFalse(active.scope.isClosed)
        assertEquals(KSLGenerationState.FAILED, candidate.state)
        assertTrue(candidate.scope.isClosed)
        assertNotNull(candidate.failure)
    }

    @Test
    fun `generation scope is restored after an evaluation exception`() {
        val manager = manager()
        val first = manager.begin("commands/help", "stamp-1", "first")
        val second = manager.begin("commands/other", "stamp-2", "second")

        manager.withCandidate(first) {
            assertSame(first.scope, manager.currentScope("commands/help"))
            assertThrows(IllegalArgumentException::class.java) {
                manager.withCandidate(second) {
                    assertSame(second.scope, manager.currentScope("commands/other"))
                    throw IllegalArgumentException("expected")
                }
            }
            assertSame(first.scope, manager.currentScope("commands/help"))
            assertNull(manager.currentScope("commands/other"))
        }

        manager.fail(first)
        manager.fail(second)
    }

    @Test
    fun `retiring and closing the manager dispose active generations`() {
        val manager = manager()
        val generation = manager.begin("chat/filter", "stamp", "compiled")
        manager.withCandidate(generation) { }
        manager.activate(generation)

        val retired = manager.retireActive("chat/filter")
        assertSame(generation, retired)
        assertNull(manager.activeGeneration("chat/filter"))
        assertEquals(KSLGenerationState.DISPOSED, generation.state)
        assertTrue(generation.scope.isClosed)

        val another = manager.begin("chat/filter", "stamp-2", "compiled-2")
        manager.withCandidate(another) { }
        manager.activate(another)
        manager.close()

        assertEquals(KSLGenerationState.DISPOSED, another.state)
        assertTrue(another.scope.isClosed)
        assertTrue(manager.snapshots().isEmpty())
    }

    @Test
    fun `duplicate candidates are rejected without leaking their scope`() {
        val scopes = mutableListOf<KSLRuntimeScope>()
        val manager = KSLGenerationManager<String> { name ->
            KSLRuntimeScope(name).also(scopes::add)
        }
        val first = manager.begin("shop", "one", "compiled-one")

        assertThrows(IllegalStateException::class.java) {
            manager.begin("shop", "two", "compiled-two")
        }

        assertEquals(1, scopes.size, "the duplicate is rejected before allocating a second scope")
        assertFalse(scopes[0].isClosed)
        assertSame(first, manager.activeGeneration("shop").let { null } ?: manager.snapshots().single().let { first })
        manager.fail(first)
    }
}
