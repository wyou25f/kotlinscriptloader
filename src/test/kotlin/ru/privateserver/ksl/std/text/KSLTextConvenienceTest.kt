package ru.privateserver.ksl.std

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.std.text.isMinecraftUsername
import ru.privateserver.ksl.std.text.normalizeWhitespace
import ru.privateserver.ksl.std.text.toCamelCase
import ru.privateserver.ksl.std.text.toSnakeCase

class KSLTextConvenienceTest {
    @Test
    fun `normalizes whitespace and identifier styles`() {
        assertEquals("hello world", "  hello\t\nworld  ".normalizeWhitespace())
        assertEquals("http_server_id", "HTTPServerID".toSnakeCase())
        assertEquals("player_profile", "PlayerProfile".toSnakeCase())
        assertEquals("playerProfile", "player_profile".toCamelCase())
        assertEquals("playerProfile", "player-profile".toCamelCase())
        assertEquals("", "   ".toCamelCase())
    }

    @Test
    fun `validates Minecraft username shape`() {
        assertTrue("Steve_123".isMinecraftUsername())
        assertFalse("ab".isMinecraftUsername())
        assertFalse("name with spaces".isMinecraftUsername())
        assertFalse("this_name_is_way_too_long".isMinecraftUsername())
    }
}
