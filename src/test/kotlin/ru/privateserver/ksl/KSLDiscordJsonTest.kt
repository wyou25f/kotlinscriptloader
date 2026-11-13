package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLDiscordJsonTest {
    @Test
    fun `json strings escape quotes slashes and control characters`() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\"", jsonString("a\"b\\c\n\r\t"))
    }

    @Test
    fun `json strings escape remaining control characters as unicode`() {
        assertEquals("\"x\\u0001y\"", jsonString("x\u0001y"))
    }

    @Test
    fun `json strings preserve ordinary unicode`() {
        assertEquals("\"Привет 🌍\"", jsonString("Привет 🌍"))
    }
}
