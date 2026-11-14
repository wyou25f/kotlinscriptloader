package ru.privateserver.ksl.std.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SecurityExtensionsTest {
    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            KSLSecurity.sha256("abc")
        )
        assertTrue(KSLSecurity.verifySha256("abc", KSLSecurity.sha256("abc")))
        assertFalse(KSLSecurity.verifySha256("abd", KSLSecurity.sha256("abc")))
        assertFalse(KSLSecurity.verifySha256("abc", "not-a-digest"))
    }

    @Test
    fun passwordHashesAreSaltedAndVerifiable() {
        val first = KSLSecurity.hashPassword("correct-password", 100_000)
        val second = KSLSecurity.hashPassword("correct-password", 100_000)
        assertNotEquals(first, second)
        assertTrue(KSLSecurity.verifyPassword("correct-password", first))
        assertFalse(KSLSecurity.verifyPassword("wrong-password", first))
        assertFalse(KSLSecurity.verifyPassword("correct-password", "invalid"))
    }

    @Test
    fun fileHashUsesFileContents() {
        val file = File.createTempFile("ksl-security-", ".txt")
        try {
            file.writeText("abc")
            assertEquals(KSLSecurity.sha256("abc"), KSLSecurity.sha256File(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun hmacAndBase64HelpersWork() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            KSLSecurity.hmacSha256("The quick brown fox jumps over the lazy dog", "key")
        )
        assertEquals("hello", KSLSecurity.base64Decode(KSLSecurity.base64Encode("hello")))
        assertNull(KSLSecurity.base64Decode("%%%"))
        val invalidUtf8 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        assertNull(KSLSecurity.base64Decode(invalidUtf8))
    }

    @Test
    fun secureTokensAreUrlSafeAndValidateLength() {
        val token = KSLSecurity.randomToken(32)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
        assertThrows(IllegalArgumentException::class.java) { KSLSecurity.randomToken(4) }
    }
}
