package ru.privateserver.ksl.api
import ru.privateserver.ksl.api.KSLLogLevel
import java.util.Locale

/** Immutable, hot-reloadable logging policy controlled by the server administrator. */
internal data class KSLLoggingPolicy(
    val defaultMinimumLevel: KSLLogLevel,
    private val ownerMinimumLevels: Map<String, KSLLogLevel>,
    val maxMessageLength: Int
) {
    fun minimumLevel(ownerKey: String): KSLLogLevel =
        ownerMinimumLevels[normalizeOwner(ownerKey)] ?: defaultMinimumLevel

    companion object {
        val DEFAULT = KSLLoggingPolicy(KSLLogLevel.INFO, emptyMap(), 4_096)

        fun parse(defaultLevel: String?, ownerLevels: Map<String, String>, maxMessageLength: Int): KSLLoggingPolicy {
            val fallback = KSLLogLevel.parseOrNull(defaultLevel) ?: KSLLogLevel.INFO
            val overrides = ownerLevels.mapNotNull { (owner, rawLevel) ->
                val key = normalizeOwner(owner)
                val level = KSLLogLevel.parseOrNull(rawLevel)
                if (key.isBlank() || level == null) null else key to level
            }.toMap()
            return KSLLoggingPolicy(fallback, overrides, maxMessageLength.coerceIn(128, 16_384))
        }

        private fun normalizeOwner(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}
