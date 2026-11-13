package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLScriptLifecycleTest {

    @Test
    fun `failed evaluation cleans partial registrations after unloading previous runtime`() {
        val resources = mutableListOf<String>()
        var unloads = 0

        val loaded = replaceScriptRuntime(
            unload = {
                unloads++
                resources.clear()
            },
            evaluate = {
                resources += listOf("listener", "scheduled-task", "placeholder")
                false
            }
        )

        assertFalse(loaded)
        assertEquals(2, unloads, "old runtime and failed partial runtime must both be cleaned")
        assertTrue(resources.isEmpty(), "failed evaluation must leave no registrations behind")
    }

    @Test
    fun `successful evaluation keeps only the new runtime registrations`() {
        val resources = mutableListOf("old-listener")
        var unloads = 0

        val loaded = replaceScriptRuntime(
            unload = {
                unloads++
                resources.clear()
            },
            evaluate = {
                resources += "new-listener"
                true
            }
        )

        assertTrue(loaded)
        assertEquals(1, unloads)
        assertEquals(listOf("new-listener"), resources)
    }

    @Test
    fun `thrown evaluation error also triggers partial runtime cleanup`() {
        val resources = mutableListOf<String>()
        var unloads = 0

        val thrown = runCatching {
            replaceScriptRuntime(
                unload = {
                    unloads++
                    resources.clear()
                },
                evaluate = {
                    resources += "partial-listener"
                    throw IllegalStateException("script evaluation failed")
                }
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(2, unloads)
        assertTrue(resources.isEmpty())
    }
}
