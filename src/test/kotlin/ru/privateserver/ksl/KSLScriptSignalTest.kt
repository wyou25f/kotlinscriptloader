package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLScriptSignalTest {
    @Test
    fun `error signal aborts current operation with its message`() {
        val signal = runCatching { throw KSLScriptError("database is unavailable") }.exceptionOrNull()
        assertTrue(signal is KSLScriptError)
        assertEquals("database is unavailable", signal?.message)
        assertEquals("error", (signal as KSLScriptSignal).level)
    }

    @Test
    fun `fatal signal is distinguishable from regular error`() {
        val signal = runCatching { throw KSLFatalScriptError("required dependency missing") }.exceptionOrNull()
        assertTrue(signal is KSLFatalScriptError)
        assertEquals("fatal", (signal as KSLScriptSignal).level)
    }
}
