package ru.privateserver.ksl.std.mixin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLMixinTest {
    @Test
    fun `builder normalizes identifiers and retains metadata`() {
        val mixin = KSLMixinBuilder(" Player.Join ", "example.kts").apply {
            description("Shared player behavior")
            install { }
        }.build()

        assertEquals("player.join", mixin.id)
        assertEquals("Shared player behavior", mixin.description)
        assertEquals(1, mixin.hookCount)
    }

    @Test
    fun `builder rejects empty mixins and invalid identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLMixinBuilder("empty", "example.kts").build()
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLMixinBuilder("not a valid id", "example.kts").apply { install { } }.build()
        }
    }

    @Test
    fun `scheduled hooks validate tick ranges`() {
        val builder = KSLMixinBuilder("valid", "example.kts")
        assertThrows(IllegalArgumentException::class.java) { builder.every(periodTicks = 0L) { } }
        assertThrows(IllegalArgumentException::class.java) { builder.after(delayTicks = -1L) { } }
    }
}