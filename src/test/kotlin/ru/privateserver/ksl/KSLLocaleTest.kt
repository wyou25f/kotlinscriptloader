package ru.privateserver.ksl

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class KSLLocaleTest {
    private val requiredMessageKeys = setOf(
        "command.no-permission",
        "language.current", "language.available", "language.usage", "language.changed", "language.invalid",
        "reload.busy", "reload.all.start", "reload.all.done", "reload.changed.done",
        "reload.folder.start", "reload.folder.empty", "reload.folder.done",
        "reload.script.start", "reload.script.done", "reload.script.failed",
        "runtime.title", "runtime.heap", "runtime.nonheap", "runtime.threads", "runtime.pool",
        "runtime.tasks", "runtime.setting", "runtime.heap-note", "runtime.workers.usage", "runtime.workers.changed",
        "scripts.empty", "scripts.title", "scripts.entry", "scripts.error-count",
        "debug.usage", "debug.not-found", "debug.title", "debug.state-loaded", "debug.state-unloaded",
        "debug.state", "debug.resources", "debug.yes", "debug.no", "debug.not-measured",
        "debug.metrics-title", "debug.metric", "debug.last-failure",
        "resources.empty", "resources.title", "resources.entry", "resources.leak",
        "deps.empty", "deps.title", "deps.none", "deps.missing",
        "addons.empty", "addons.title", "addons.dependency",
        "services.empty", "services.title",
        "discord.empty", "discord.channels",
        "libraries.empty", "libraries.title", "libraries.owner",
        "errors.unknown-category", "errors.catalog-title", "errors.catalog-all", "errors.catalog-next",
        "errors.summary", "errors.catalog-command", "errors.last", "errors.code", "errors.none",
        "error.usage", "error.not-found", "error.title", "error.hint",
        "watchdog.disabled", "watchdog.active", "watchdog.stall", "watchdog.dump", "watchdog.guide",
        "validate.start", "validate.ok", "validate.failed",
        "doctor.title", "doctor.ok", "doctor.attention",
        "dbmigrate.sqlite", "dbmigrate.read", "dbmigrate.not-found", "dbmigrate.found",
        "dbmigrate.copy", "dbmigrate.confirm", "dbmigrate.done",
        "sandbox.disabled", "sandbox.enabled", "sandbox.level-strict", "sandbox.level-text",
        "sandbox.level", "sandbox.trusted", "sandbox.runtime", "sandbox.description", "sandbox.blocklist",
        "help.unknown", "help.title", "help.reload", "help.runtime", "help.scripts", "help.debug",
        "help.resources", "help.deps", "help.addons", "help.services", "help.libraries", "help.discord",
        "help.sandbox", "help.errors", "help.watchdog", "help.error", "help.doctor", "help.validate",
        "help.dbmigrate", "help.language",
        "script-command.players-only", "script-command.no-permission", "script-command.failed",
        "background.error", "lifecycle.starting", "lifecycle.stopping"
    )

    private fun loadLocale(name: String): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream("locale/$name.yml")
        assertNotNull(stream, "Missing locale/$name.yml")
        return stream!!.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                val yaml = YamlConfiguration.loadConfiguration(reader)
                yaml.getKeys(true)
                    .filterNot(yaml::isConfigurationSection)
                    .associateWith { key -> yaml.getString(key)!! }
            }
        }
    }

    private fun placeholders(message: String): Set<String> =
        Regex("""\{([A-Za-z0-9_-]+)}""")
            .findAll(message)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun renders_message_and_named_placeholders() {
        val bundle = KSLMessageBundle(mapOf("reload.done" to "Loaded {count} scripts"))
        assertEquals("Loaded 4 scripts", bundle.render("reload.done", mapOf("count" to 4)))
    }

    @Test
    fun preserves_formatting_codes_and_replaces_null_values() {
        val bundle = KSLMessageBundle(mapOf("notice" to "§aHello {player}!"))
        assertEquals("§aHello !", bundle.render("notice", mapOf("player" to null)))
    }

    @Test
    fun returns_a_diagnostic_for_a_missing_translation_key() {
        val bundle = KSLMessageBundle(emptyMap())
        assertEquals("§cMissing locale key: command.missing", bundle.render("command.missing"))
    }

    @Test
    fun bundled_locales_contain_every_nested_server_message_key() {
        val english = loadLocale("en")
        val russian = loadLocale("ru")
        val template = loadLocale("template")

        assertTrue(requiredMessageKeys.all(english::containsKey), "English locale is missing required keys: \${requiredMessageKeys - english.keys}")
        assertTrue(requiredMessageKeys.all(russian::containsKey), "Russian locale is missing required keys: \${requiredMessageKeys - russian.keys}")
        assertTrue(requiredMessageKeys.all(template::containsKey), "Locale template is missing required keys: \${requiredMessageKeys - template.keys}")
    }

    @Test
    fun bundled_locales_and_template_have_identical_keys_and_placeholders() {
        val english = loadLocale("en")
        val russian = loadLocale("ru")
        val template = loadLocale("template")

        assertEquals(english.keys, russian.keys, "Russian locale keys must match English")
        assertEquals(english.keys, template.keys, "Locale template keys must match English")

        english.forEach { (key, message) ->
            val expected = placeholders(message)
            assertEquals(expected, placeholders(russian.getValue(key)), "Placeholder mismatch in ru.yml at '$key'")
            assertEquals(expected, placeholders(template.getValue(key)), "Placeholder mismatch in template.yml at '$key'")
        }
    }

    @Test
    fun nested_keys_resolve_through_bukkit_yaml_configuration() {
        val english = loadLocale("en")
        val russian = loadLocale("ru")

        assertEquals("§cYou do not have permission. Required: ksl.admin", english["command.no-permission"])
        assertEquals("§cНет прав. Требуется: ksl.admin", russian["command.no-permission"])
        assertTrue(english.getValue("reload.all.start").isNotBlank())
        assertTrue(russian.getValue("runtime.workers.changed").contains("{count}"))
    }
}
