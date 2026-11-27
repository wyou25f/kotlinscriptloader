package ru.privateserver.ksl.script

import kotlin.script.experimental.api.ScriptCompilationConfiguration

/**
 * Reuses the immutable compilation configuration while the addon/library environment is stable.
 *
 * Only the current environment is retained. Replacing the key releases the previous configuration
 * from this cache instead of accumulating one classloader snapshot per reload.
 */
internal class KSLCompilationConfigCache {
    private data class Entry(
        val environmentStamp: String,
        val configuration: ScriptCompilationConfiguration
    )

    private val lock = Any()
    @Volatile private var entry: Entry? = null

    fun getOrBuild(
        environmentStamp: String,
        builder: () -> ScriptCompilationConfiguration
    ): ScriptCompilationConfiguration {
        require(environmentStamp.isNotBlank()) { "environmentStamp must not be blank" }
        entry?.let { current ->
            if (current.environmentStamp == environmentStamp) return current.configuration
        }
        return synchronized(lock) {
            entry?.let { current ->
                if (current.environmentStamp == environmentStamp) return@synchronized current.configuration
            }
            builder().also { configuration ->
                entry = Entry(environmentStamp, configuration)
            }
        }
    }

    fun clear() {
        synchronized(lock) { entry = null }
    }
}
