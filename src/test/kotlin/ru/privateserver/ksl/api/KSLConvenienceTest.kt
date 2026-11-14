package ru.privateserver.ksl.api

import be.seeseemelk.mockbukkit.MockBukkit
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class KSLConvenienceTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun startServer() { MockBukkit.mock() }

        @JvmStatic
        @AfterAll
        fun stopServer() { MockBukkit.unmock() }
    }

    private fun api(): KSLAPI =
        Proxy.newProxyInstance(KSLAPI::class.java.classLoader, arrayOf(KSLAPI::class.java)) { _, method, _ ->
            throw UnsupportedOperationException("Unexpected KSLAPI method: " + method.name)
        } as KSLAPI

    @Test
    fun onlinePlayerHelpersReturnEmptyResultsForOfflinePlayers() {
        val api = api()
        val id = UUID.randomUUID()

        assertEquals(emptyList<Player>(), api.onlinePlayers())
        assertEquals(0, api.onlinePlayerCount())
        assertNull(api.findOnlinePlayer("player-that-is-not-online"))
        assertNull(api.findOnlinePlayer(id))
        assertFalse(api.isPlayerOnline(id))
    }

    @Test
    fun richBroadcastAcceptsMiniMessageWithoutRequiringAnOnlinePlayer() {
        assertEquals(0, api().broadcastRich("<green>server ready</green>"))
    }
}
