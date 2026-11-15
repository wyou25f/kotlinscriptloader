package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLSoundEngineTest {
    @Test
    fun `bare sound keys use the minecraft namespace`() {
        assertEquals("minecraft:entity.player.levelup", normalizeKSLSoundKey("ENTITY.PLAYER.LEVELUP"))
    }

    @Test
    fun `namespaced sound keys are normalized without changing their namespace`() {
        assertEquals("custompack:ui.confirm", normalizeKSLSoundKey("CustomPack:UI.Confirm"))
    }

    @Test
    fun `invalid sound keys are rejected`() {
        listOf("", "minecraft:", "bad key", "a:b:c", "minecraft:bad key").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeKSLSoundKey(key)
            }
        }
    }
}
