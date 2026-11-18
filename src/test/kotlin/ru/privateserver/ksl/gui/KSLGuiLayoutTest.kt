package ru.privateserver.ksl.gui

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KSLGuiLayoutTest {
    @Test
    fun `rejects invalid inventory sizes and empty element slots`() {
        assertNotNull(KSLGuiLayout.validationError(0, listOf(1), -1, -1))
        assertNotNull(KSLGuiLayout.validationError(27, emptyList(), -1, -1))
    }

    @Test
    fun `rejects out of range and duplicate element slots`() {
        assertNotNull(KSLGuiLayout.validationError(27, listOf(0, 27), -1, -1))
        assertNotNull(KSLGuiLayout.validationError(27, listOf(1, 1), -1, -1))
    }

    @Test
    fun `rejects invalid or overlapping navigation slots`() {
        assertNotNull(KSLGuiLayout.validationError(27, listOf(1, 2), 2, -1))
        assertNotNull(KSLGuiLayout.validationError(27, listOf(1, 2), 25, 25))
        assertNotNull(KSLGuiLayout.validationError(27, listOf(10, 11), -2, -1))
    }

    @Test
    fun `accepts valid pagination slots`() {
        assertNull(KSLGuiLayout.validationError(27, listOf(10, 11, 12), 18, 26))
    }
}
