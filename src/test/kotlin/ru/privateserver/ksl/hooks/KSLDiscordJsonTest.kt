package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLDiscordJsonTest {
    @Test
    fun `webhook URLs must target Discord HTTPS webhook endpoints`() {
        assertEquals(true, isDiscordWebhookUrl("https://discord.com/api/webhooks/123/token"))
        assertEquals(true, isDiscordWebhookUrl("https://discordapp.com/api/webhooks/123/token"))
        assertEquals(false, isDiscordWebhookUrl("http://discord.com/api/webhooks/123/token"))
        assertEquals(false, isDiscordWebhookUrl("https://example.com/api/webhooks/123/token"))
        assertEquals(false, isDiscordWebhookUrl("https://discord.com.evil.example/api/webhooks/123/token"))
        assertEquals(false, isDiscordWebhookUrl("not a URL"))
    }

    @Test
    fun `json strings escape quotes slashes and control characters`() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\"", jsonString("a\"b\\c\n\r\t"))
    }

    @Test
    fun `json strings escape remaining control characters as unicode`() {
        assertEquals("\"x\\u0001y\"", jsonString("x\u0001y"))
    }

    @Test
    fun `json strings preserve ordinary unicode`() {
        assertEquals("\"Привет 🌍\"", jsonString("Привет 🌍"))
    }
}
