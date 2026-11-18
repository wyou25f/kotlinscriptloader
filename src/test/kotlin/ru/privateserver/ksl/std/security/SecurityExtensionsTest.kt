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
    fun sha512SupportsTextAndStreamingFiles() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            KSLSecurity.sha512("abc")
        )
        assertTrue(KSLSecurity.verifySha512("abc", KSLSecurity.sha512("abc")))
        assertFalse(KSLSecurity.verifySha512("abd", KSLSecurity.sha512("abc")))
        assertFalse(KSLSecurity.verifySha512("abc", KSLSecurity.sha256("abc")))
        val file = File.createTempFile("ksl-security-sha512-", ".txt")
        try {
            file.writeText("abc")
            assertEquals(KSLSecurity.sha512("abc"), KSLSecurity.sha512File(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun hmacVerificationAndPasswordRehashPolicyAreSafe() {
        val signature = KSLSecurity.hmacSha256("payload", "secret")
        assertTrue(KSLSecurity.verifyHmacSha256("payload", "secret", signature))
        assertFalse(KSLSecurity.verifyHmacSha256("changed", "secret", signature))
        assertFalse(KSLSecurity.verifyHmacSha256("payload", "wrong", signature))
        assertFalse(KSLSecurity.verifyHmacSha256("payload", "secret", "invalid"))

        val oldHash = KSLSecurity.hashPassword("password", 100_000)
        assertTrue(KSLSecurity.needsPasswordRehash(oldHash, 200_000))
        assertFalse(KSLSecurity.needsPasswordRehash(oldHash, 100_000))
        assertTrue(KSLSecurity.needsPasswordRehash("malformed"))
        assertThrows(IllegalArgumentException::class.java) {
            KSLSecurity.needsPasswordRehash(oldHash, 10)
        }
    }

    @Test
    fun passwordVerifierRejectsOversizedEncodedInput() {
        assertFalse(KSLSecurity.verifyPassword("password", "x".repeat(100_000)))
    }

    @Test
    fun secureTokensAreUrlSafeAndValidateLength() {
        val token = KSLSecurity.randomToken(32)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
        assertThrows(IllegalArgumentException::class.java) { KSLSecurity.randomToken(4) }
    }
}
