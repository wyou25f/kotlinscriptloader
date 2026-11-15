package ru.privateserver.ksl.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLMinecraftVersionTest {

    @Test
    fun `parses server version suffixes without treating them as part of version`() {
        assertEquals(KSLMinecraftVersion(1, 16, 5), KSLMinecraftVersion.parse("1.16.5-R0.1-SNAPSHOT"))
        assertEquals(KSLMinecraftVersion(1, 21, 1), KSLMinecraftVersion.parse("git-Paper-1.21.1"))
        assertEquals(KSLMinecraftVersion(1, 20, 0), KSLMinecraftVersion.parse("1.20"))
    }

    @Test
    fun `unknown version strings are not guessed`() {
        assertNull(KSLMinecraftVersion.parse(null))
        assertNull(KSLMinecraftVersion.parse(""))
        assertNull(KSLMinecraftVersion.parse("Paper build unknown"))
    }

    @Test
    fun `version comparison includes patch level`() {
        assertTrue(KSLMinecraftVersion(1, 16, 5) < KSLMinecraftVersion(1, 18, 2))
        assertTrue(KSLMinecraftVersion(1, 21, 1) < KSLMinecraftVersion(1, 21, 2))
        assertTrue(KSLMinecraftVersion(1, 21, 1).isAtLeast(1, 21))
        assertFalse(KSLMinecraftVersion(1, 16, 5).isAtLeast(1, 18, 2))
    }

    @Test
    fun `reflection probes tolerate missing classes and members`() {
        assertTrue(KSLServerCompatibility.classAvailable("java.lang.String"))
        assertFalse(KSLServerCompatibility.classAvailable("not.a.real.ServerClass"))
        assertTrue(KSLServerCompatibility.methodAvailable("java.lang.String", "substring", 1))
        assertFalse(KSLServerCompatibility.methodAvailable("java.lang.String", "notAMethod", 0))
        assertTrue(KSLServerCompatibility.enumConstantAvailable("java.lang.Thread\$State", "RUNNABLE"))
        assertFalse(KSLServerCompatibility.enumConstantAvailable("java.lang.String", "RUNNABLE"))
    }

    @Test
    fun `branch declares its own compatibility target`() {
        assertEquals("mc-1.16.5-to-1.21.x", KSLCompatibilityTarget.TRACK_ID)
        assertEquals("1.16.5", KSLCompatibilityTarget.MINIMUM_MINECRAFT_VERSION)
        assertEquals(16, KSLCompatibilityTarget.MINIMUM_JAVA_VERSION)
        assertEquals("Paper 1.16.5", KSLCompatibilityTarget.COMPILE_API_BASELINE)
    }
}
