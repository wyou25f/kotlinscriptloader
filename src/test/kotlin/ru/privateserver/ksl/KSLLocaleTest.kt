package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLLocaleTest {
    @Test
    fun `renders message and named placeholders`() {
        val bundle = KSLMessageBundle(mapOf("reload.done" to "Loaded {count} scripts"))
        assertEquals("Loaded 4 scripts", bundle.render("reload.done", mapOf("count" to 4)))
    }

    @Test
    fun `preserves formatting codes and safely replaces null values`() {
        val bundle = KSLMessageBundle(mapOf("notice" to "§aHello {player}!"))
        assertEquals("§aHello !", bundle.render("notice", mapOf("player" to null)))
    }

    @Test
    fun `returns a diagnostic for a missing translation key`() {
        val bundle = KSLMessageBundle(emptyMap())
        assertEquals("§cMissing locale key: command.missing", bundle.render("command.missing"))
    }
}
