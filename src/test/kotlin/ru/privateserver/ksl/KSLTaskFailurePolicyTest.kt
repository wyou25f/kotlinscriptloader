package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLTaskFailurePolicyTest {

    @Test
    fun `stops only after the configured number of consecutive failures`() {
        val policy = KSLTaskFailurePolicy(3)

        assertFalse(policy.onFailure())
        assertFalse(policy.onFailure())
        assertTrue(policy.onFailure())
        assertTrue(policy.onFailure())
    }

    @Test
    fun `successful execution resets the failure streak`() {
        val policy = KSLTaskFailurePolicy(2)

        assertFalse(policy.onFailure())
        policy.onSuccess()
        assertFalse(policy.onFailure())
        assertTrue(policy.onFailure())
    }
}
