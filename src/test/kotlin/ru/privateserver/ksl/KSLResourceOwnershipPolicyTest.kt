package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLResourceOwnershipPolicyTest {
    @Test
    fun addonCanRemoveOnlyItsOwnResources() {
        assertTrue(KSLResourceOwnershipPolicy.mayUnregister("alpha", "alpha"))
        assertFalse(KSLResourceOwnershipPolicy.mayUnregister("alpha", "beta"))
        assertFalse(KSLResourceOwnershipPolicy.mayUnregister("alpha", null))
        assertTrue(KSLResourceOwnershipPolicy.mayUnregister(null, "beta"))
        assertTrue(KSLResourceOwnershipPolicy.mayUnregister(null, null))
    }
}
