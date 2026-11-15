package ru.privateserver.ksl.std.mixin

import org.bukkit.event.player.PlayerJoinEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLMixinTest {
    @Test
    fun `builder collects event and task hooks`() {
        val mixin = KSLMixinBuilder("player-tools", "example-script").apply {
            description("  Common player behavior  ")
            on<PlayerJoinEvent> { player.name }
            every(periodTicks = 20L) { onlineCount }
            after(delayTicks = 5L) { maxPlayers }
        }.build()
        assertEquals("player-tools", mixin.id)
        assertEquals("Common player behavior", mixin.description)
        assertEquals(3, mixin.hookCount)
    }

    @Test
    fun `builder rejects invalid ids and empty mixins`() {
        assertThrows(IllegalArgumentException::class.java) { KSLMixinBuilder("not a valid id", "example-script").build() }
        assertThrows(IllegalArgumentException::class.java) { KSLMixinBuilder("empty", "example-script").build() }
    }

    @Test
    fun `builder rejects invalid task timing`() {
        val builder = KSLMixinBuilder("timing", "example-script")
        assertThrows(IllegalArgumentException::class.java) { builder.every(0L) {} }
        assertThrows(IllegalArgumentException::class.java) { builder.after(-1L) {} }
    }
}