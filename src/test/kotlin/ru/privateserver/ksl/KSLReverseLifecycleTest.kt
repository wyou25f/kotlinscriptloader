package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLReverseLifecycleTest {
    @Test
    fun runsTeardownInReverseOrderAndContinuesAfterFailure() {
        val order = mutableListOf<Int>()
        val failures = mutableListOf<Int>()

        KSLReverseLifecycle.run(
            items = listOf(1, 2, 3),
            action = {
                order += it
                if (it == 2) error("module teardown failed")
            },
            onFailure = { item, _ -> failures += item }
        )

        assertEquals(listOf(3, 2, 1), order)
        assertEquals(listOf(2), failures)
    }
}
