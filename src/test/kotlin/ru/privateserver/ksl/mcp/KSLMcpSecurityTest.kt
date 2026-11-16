package ru.privateserver.ksl.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class KSLMcpSecurityTest {
    @Test
    fun `only literal loopback addresses are accepted`() {
        assertTrue(KSLMcpSecurity.isLoopbackBind("127.0.0.1"))
        assertTrue(KSLMcpSecurity.isLoopbackBind("::1"))
        assertFalse(KSLMcpSecurity.isLoopbackBind("localhost"))
        assertFalse(KSLMcpSecurity.isLoopbackBind("0.0.0.0"))
        assertFalse(KSLMcpSecurity.isLoopbackBind("192.168.1.10"))
    }

    @Test
    fun `origin must be absent or exactly allowlisted`() {
        val allowed = setOf("http://localhost:3000")
        assertTrue(KSLMcpSecurity.originAllowed(null, allowed))
        assertTrue(KSLMcpSecurity.originAllowed("http://localhost:3000", allowed))
        assertFalse(KSLMcpSecurity.originAllowed("http://localhost:3001", allowed))
        assertFalse(KSLMcpSecurity.originAllowed("https://attacker.example", allowed))
    }

    @Test
    fun `wildcard and non origin urls are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLMcpSecurity.validateOrigins(listOf("*"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLMcpSecurity.validateOrigins(listOf("http://localhost:3000/path"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLMcpSecurity.validateOrigins(listOf("file:///etc/passwd"))
        }
        assertTrue(KSLMcpSecurity.validateOrigins(listOf("https://localhost:443")).contains("https://localhost:443"))
    }

    @Test
    fun `bearer token comparison requires exact secret`() {
        assertTrue(KSLMcpSecurity.bearerTokenMatches("Bearer aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse(KSLMcpSecurity.bearerTokenMatches("Bearer aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse(KSLMcpSecurity.bearerTokenMatches("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse(KSLMcpSecurity.bearerTokenMatches("Bearer ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun `request body reader rejects oversized payloads`() {
        val input = ByteArrayInputStream(ByteArray(20) { 1 })
        assertThrows(IllegalArgumentException::class.java) {
            KSLMcpSecurity.readBounded(input, 10)
        }
        assertTrue(KSLMcpSecurity.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `access token must be long printable ascii`() {
        assertTrue(KSLMcpSecurity.validToken("x".repeat(32)))
        assertFalse(KSLMcpSecurity.validToken("x".repeat(31)))
        assertFalse(KSLMcpSecurity.validToken("x".repeat(257)))
        assertFalse(KSLMcpSecurity.validToken("x".repeat(31) + "\n"))
        assertFalse(KSLMcpSecurity.validToken("x".repeat(31) + "!"))
    }
    @Test
    fun `host header is strictly limited to loopback authority and configured port`() {
        assertTrue(KSLMcpSecurity.validHostHeader("127.0.0.1:8765", 8765))
        assertTrue(KSLMcpSecurity.validHostHeader("localhost:8765", 8765))
        assertTrue(KSLMcpSecurity.validHostHeader("[::1]:8765", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader("127.0.0.1.evil.example:8765", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader("evil.example:8765", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader("127.0.0.1:8766", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader(null, 8765))
    }

    @Test
    fun `accept header must allow both streamable response formats`() {
        assertTrue(KSLMcpSecurity.acceptsMcpResponse("application/json, text/event-stream"))
        assertTrue(KSLMcpSecurity.acceptsMcpResponse("application/json;q=1.0, text/event-stream;q=0.5"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json;q=0, text/event-stream"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json;q=2, text/event-stream"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json;q=invalid, text/event-stream"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse(null))
    }

    @Test
    fun `host header is strictly limited to loopback authority and configured port`() {
        assertTrue(KSLMcpSecurity.validHostHeader("127.0.0.1:8765", 8765))
        assertTrue(KSLMcpSecurity.validHostHeader("localhost:8765", 8765))
        assertTrue(KSLMcpSecurity.validHostHeader("[::1]:8765", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader("127.0.0.1.evil.example:8765", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader("evil.example:8765", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader("127.0.0.1:8766", 8765))
        assertFalse(KSLMcpSecurity.validHostHeader(null, 8765))
    }

    @Test
    fun `accept header must allow both streamable response formats`() {
        assertTrue(KSLMcpSecurity.acceptsMcpResponse("application/json, text/event-stream"))
        assertTrue(KSLMcpSecurity.acceptsMcpResponse("application/json;q=1.0, text/event-stream;q=0.5"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json;q=0, text/event-stream"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json;q=2, text/event-stream"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse("application/json;q=invalid, text/event-stream"))
        assertFalse(KSLMcpSecurity.acceptsMcpResponse(null))
    }

}
