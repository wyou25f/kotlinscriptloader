package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLScriptInfoTest {
    @Test
    fun registryStateDefaultsRemainUsefulForExistingFourArgumentCallers() {
        assertEquals(KSLScriptState.LOADED, KSLScriptInfo("active", true, 12L, 0).state)
        assertEquals(KSLScriptState.FAILED, KSLScriptInfo("broken", false, -1L, 1).state)
        assertEquals(KSLScriptState.UNLOADED, KSLScriptInfo("idle", false, -1L, 0).state)
    }

    @Test
    fun registrySnapshotCanDescribeDegradedRuntimeAndMissingSource() {
        val info = KSLScriptInfo("economy/shop", true, 24L, 1)
        info.state = KSLScriptState.DEGRADED
        info.lastError = "Compilation failed"
        info.lastAttemptAtMillis = 1_800_000_000_000L
        info.sourceExists = false

        assertTrue(info.loaded)
        assertEquals(KSLScriptState.DEGRADED, info.state)
        assertEquals("Compilation failed", info.lastError)
        assertEquals(1_800_000_000_000L, info.lastAttemptAtMillis)
        assertFalse(info.sourceExists)
    }
}
