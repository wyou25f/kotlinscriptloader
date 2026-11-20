package ru.privateserver.ksl.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KSLSourceFingerprintTest {
    @Test fun `identical source has stable fingerprint`() {
        assertEquals(KSLSourceFingerprint.of("val answer = 42"), KSLSourceFingerprint.of("val answer = 42"))
    }
    @Test fun `SHA-256 uses canonical lowercase hexadecimal encoding`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            KSLSourceFingerprint.of("abc")
        )
        assertEquals(
            KSLSourceFingerprint.of("abc"),
            KSLSourceFingerprint.of("abc".toByteArray(Charsets.UTF_8))
        )
    }
    @Test fun `same length source changes have different fingerprints`() {
        assertNotEquals(KSLSourceFingerprint.of("val answer = 42"), KSLSourceFingerprint.of("val answer = 43"))
    }
}