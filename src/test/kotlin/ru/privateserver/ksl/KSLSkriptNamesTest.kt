package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLSkriptNamesTest {
    @Test
    fun variableNamesAcceptRawAndWrappedForms() {
        assertEquals("profile::coins", KSLSkriptNames.variableName("profile::coins"))
        assertEquals("profile::coins", KSLSkriptNames.variableName("{profile::coins}"))
        assertThrows(IllegalArgumentException::class.java) { KSLSkriptNames.variableName("  ") }
        assertThrows(IllegalArgumentException::class.java) { KSLSkriptNames.variableName("{bad\nname}") }
    }

    @Test
    fun commandLabelExtractsFirstTokenSafely() {
        assertEquals("warp", KSLSkriptNames.commandLabel("/warp home"))
        assertEquals("spawn", KSLSkriptNames.commandLabel("spawn"))
        assertNull(KSLSkriptNames.commandLabel(" "))
        assertNull(KSLSkriptNames.commandLabel("warp\nstop"))
    }

    @Test
    fun scriptPathsNormalizeExtensionAndRejectTraversal() {
        assertEquals("events/join.sk", KSLSkriptNames.scriptPath("./events/join"))
        assertEquals("events/join.sk", KSLSkriptNames.scriptPath("events\\join.sk"))
        assertNull(KSLSkriptNames.scriptPath("../secrets"))
        assertNull(KSLSkriptNames.scriptPath("events/../secret.sk"))
        assertNull(KSLSkriptNames.scriptPath("folder/my script"))
    }
}
