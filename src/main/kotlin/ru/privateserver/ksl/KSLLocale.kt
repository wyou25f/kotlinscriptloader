package ru.privateserver.ksl

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads server-facing messages from plugins/KotlinScriptLoader/locale.
 * Language files are data-only. The selected locale is merged over English defaults.
 */
internal class KSLLocaleManager(private val plugin: KotlinScriptLoaderPlugin) {
    private val localeDirectory = File(plugin.dataFolder, "locale")
    private val bundle = AtomicReference(KSLMessageBundle(emptyMap()))

    @Volatile
    var activeLanguage: String = "ru"
        private set

    fun reload(requestedLanguage: String? = plugin.config.getString("language", "ru")): String {
        ensureBundledFiles()
        val requested = normalizeLanguage(requestedLanguage) ?: "ru"
        val selectedFile = File(localeDirectory, "$requested.yml")
        val selectedLanguage = when {
            selectedFile.isFile -> requested
            File(localeDirectory, "ru.yml").isFile -> {
                if (requested != "ru") {
                    plugin.logger.warning("Unknown KSL language '$requested'; falling back to 'ru'. Add locale/$requested.yml to provide it.")
                }
                "ru"
            }
            else -> "en"
        }

        val english = readMessages(File(localeDirectory, "en.yml"))
        val russian = readMessages(File(localeDirectory, "ru.yml"))
        val selected = when (selectedLanguage) {
            "en" -> english
            "ru" -> russian
            else -> readMessages(File(localeDirectory, "$selectedLanguage.yml"))
        }

        val merged = LinkedHashMap<String, String>()
        if (selectedLanguage == "en") {
            merged.putAll(russian)
            merged.putAll(english)
        } else {
            merged.putAll(english)
        }
        merged.putAll(selected)

        bundle.set(KSLMessageBundle(merged))
        activeLanguage = selectedLanguage
        return selectedLanguage
    }

    fun message(key: String, vararg values: Pair<String, Any?>): String =
        bundle.get().render(key, values.toMap())

    fun availableLanguages(): List<String> {
        if (!localeDirectory.isDirectory) return emptyList()
        return localeDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .mapNotNull { file ->
                val normalized = normalizeLanguage(file.nameWithoutExtension)
                normalized?.takeIf { it == file.nameWithoutExtension && it != "template" }
            }
            .distinct()
            .sorted()
            .toList()
    }

    fun containsLanguage(language: String): Boolean {
        val normalized = normalizeLanguage(language) ?: return false
        return normalized in availableLanguages()
    }

    private fun ensureBundledFiles() {
        if (!localeDirectory.exists() && !localeDirectory.mkdirs()) {
            plugin.logger.warning("Could not create KSL locale directory: ${localeDirectory.absolutePath}")
            return
        }
        listOf("locale/en.yml", "locale/ru.yml", "locale/template.yml").forEach { resource ->
            val destination = File(plugin.dataFolder, resource)
            if (!destination.isFile) {
                runCatching { plugin.saveResource(resource, false) }
                    .onFailure { plugin.logger.warning("Could not install default locale file '$resource': ${it.message}") }
            }
        }
    }

    private fun readMessages(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val yaml = YamlConfiguration.loadConfiguration(file)
            yaml.getKeys(true)
                .asSequence()
                .filterNot(yaml::isConfigurationSection)
                .mapNotNull { key -> yaml.getString(key)?.let { key to it } }
                .toMap()
        }.onFailure {
            plugin.logger.warning("Could not read KSL locale file '${file.name}': ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun normalizeLanguage(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: return null
        if (normalized.length !in 2..16) return null
        if (!normalized.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) return null
        return normalized
    }
}

/** Immutable, testable message lookup with named placeholder substitution. */
internal class KSLMessageBundle(private val messages: Map<String, String>) {
    fun render(key: String, values: Map<String, Any?> = emptyMap()): String {
        var result = messages[key] ?: "§cMissing locale key: $key"
        values.forEach { (name, value) ->
            result = result.replace("{$name}", value?.toString() ?: "")
        }
        return result
    }
}
