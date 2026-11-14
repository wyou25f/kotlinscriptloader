package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-script memory cache for persist properties.
 *
 * Missing rows and failed database reads both fall back to the property's default.
 * The fallback is cached so an unavailable database is not queried on every access.
 * Script reload clears the entries through clearScript().
 */
internal class KSLPersistMemoryCache {
    // ConcurrentHashMap cannot store null; keep persisted null distinct from a missing value.
    private object Null
    private val values = ConcurrentHashMap<String, Any>()

    fun <T> get(
        fullKey: String,
        default: () -> T,
        load: () -> Pair<Boolean, Any?>?
    ): T {
        val cached = values.compute(fullKey) { _, current ->
            current ?: run {
                val loaded = load()
                if (loaded?.first == true) loaded.second ?: Null
                else default() ?: Null
            }
        } ?: return default()

        @Suppress("UNCHECKED_CAST")
        return (if (cached === Null) null else cached) as T
    }

    fun set(fullKey: String, value: Any?) {
        values[fullKey] = value ?: Null
    }

    fun clearScript(scriptName: String) {
        values.keys.removeIf { it.startsWith("$scriptName:") }
    }
}
