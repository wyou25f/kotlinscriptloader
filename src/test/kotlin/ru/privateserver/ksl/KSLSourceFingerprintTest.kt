package ru.privateserver.ksl

import ru.privateserver.ksl.script.KSLSourceFingerprint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KSLSourceFingerprintTest {
    @Test fun `identical source has stable fingerprint`() {
        assertEquals(KSLSourceFingerprint.of("val answer = 42"), KSLSourceFingerprint.of("val answer = 42"))
    }
    @Test fun `same length source changes have different fingerprints`() {
        assertNotEquals(KSLSourceFingerprint.of("val answer = 42"), KSLSourceFingerprint.of("val answer = 43"))
    }
}