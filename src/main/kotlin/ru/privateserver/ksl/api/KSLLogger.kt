package ru.privateserver.ksl.api

import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/** Severity requested by a script/addon logger. The server administrator can enforce a stricter minimum. */
enum class KSLLogLevel(val priority: Int) {
    TRACE(0), DEBUG(10), INFO(20), WARN(30), ERROR(40), OFF(Int.MAX_VALUE);

    companion object {
        @JvmStatic
        fun parseOrNull(value: String?): KSLLogLevel? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return when (normalized) {
                "WARNING" -> WARN
                "SEVERE" -> ERROR
                "FINE", "FINER", "FINEST" -> DEBUG
                else -> entries.firstOrNull { it.name == normalized }
            }
        }
    }
}

/** Per-logger preferences. The server administrator's minimum level always takes precedence. */
class KSLLoggerConfig {
    /** Defaults to TRACE so the server's configured minimum controls visibility. */
    var level: KSLLogLevel = KSLLogLevel.TRACE
    var prefix: String = ""
    var includeOwner: Boolean = true
    var includeLoggerName: Boolean = true

    internal fun snapshot(): KSLLoggerConfig = KSLLoggerConfig().also {
        it.level = level
        it.prefix = prefix
        it.includeOwner = includeOwner
        it.includeLoggerName = includeLoggerName
    }
}

internal data class KSLLogRecord(
    val ownerKey: String,
    val loggerName: String,
    val level: KSLLogLevel,
    val prefix: String,
    val message: String,
    val fields: List<Pair<String, String>>,
    val includeOwner: Boolean,
    val includeLoggerName: Boolean,
    val cause: Throwable?
) {
    fun render(maxLength: Int): String {
        val limit = maxLength.coerceIn(128, 16_384)
        val result = buildString {
            if (includeOwner) append('[').append(ownerKey).append("] ")
            if (includeLoggerName && loggerName.isNotBlank()) append('[').append(loggerName).append("] ")
            if (prefix.isNotBlank()) append('[').append(prefix).append("] ")
            append(message)
            if (fields.isNotEmpty()) {
                append(" | ")
                append(fields.joinToString(" ") { (key, value) -> "$key=$value" })
            }
        }
        return if (result.length <= limit) result else result.take(limit - 1) + "…"
    }
}

/**
 * Structured logger for KSL scripts and addons.
 * Effective threshold = max(logger preference, administrator minimum).
 */
class KSLLogger internal constructor(
    private val ownerKey: String,
    private val loggerName: String,
    configuration: KSLLoggerConfig,
    private val administratorMinimumLevel: () -> KSLLogLevel,
    private val maxMessageLength: () -> Int,
    private val sink: (KSLLogRecord) -> Unit
) {
    private val config = configuration.snapshot()

    fun isEnabled(level: KSLLogLevel): Boolean {
        if (level == KSLLogLevel.OFF) return false
        val adminMinimum = administratorMinimumLevel()
        val threshold = if (config.level.priority >= adminMinimum.priority) config.level else adminMinimum
        return level.priority >= threshold.priority
    }

    fun trace(message: String, vararg fields: Pair<String, Any?>) = emit(KSLLogLevel.TRACE, message, null, fields)
    fun debug(message: String, vararg fields: Pair<String, Any?>) = emit(KSLLogLevel.DEBUG, message, null, fields)
    fun info(message: String, vararg fields: Pair<String, Any?>) = emit(KSLLogLevel.INFO, message, null, fields)
    fun warn(message: String, vararg fields: Pair<String, Any?>) = emit(KSLLogLevel.WARN, message, null, fields)
    fun error(message: String, vararg fields: Pair<String, Any?>) = emit(KSLLogLevel.ERROR, message, null, fields)
    fun warn(message: String, cause: Throwable, vararg fields: Pair<String, Any?>) = emit(KSLLogLevel.WARN, message, cause, fields)
    fun error(message: String, cause: Throwable, vararg fields: Pair<String, Any?>) = emit(KSLLogLevel.ERROR, message, cause, fields)

    fun child(category: String): KSLLogger {
        require(category.isNotBlank()) { "Logger child category must not be blank" }
        val childName = if (loggerName.isBlank()) category else "$loggerName.$category"
        return KSLLogger(ownerKey, childName, config, administratorMinimumLevel, maxMessageLength, sink)
    }

    private fun emit(level: KSLLogLevel, message: String, cause: Throwable?, fields: Array<out Pair<String, Any?>>) {
        if (!isEnabled(level)) return
        val safeFields = fields.take(MAX_FIELDS).mapNotNull { (rawKey, rawValue) ->
            val key = sanitize(rawKey, 48)
            if (key.isBlank()) null else key to sanitize(rawValue?.toString() ?: "null", 512)
        }
        sink(
            KSLLogRecord(
                ownerKey = sanitize(ownerKey, 160),
                loggerName = sanitize(loggerName, 96),
                level = level,
                prefix = sanitize(config.prefix, 96),
                message = sanitize(message, maxMessageLength().coerceIn(128, 16_384)),
                fields = safeFields,
                includeOwner = config.includeOwner,
                includeLoggerName = config.includeLoggerName,
                cause = cause
            )
        )
    }

    private fun sanitize(value: String, maxLength: Int): String =
        value.replace("\r", " ").replace("\n", "\\n").replace("\t", " ").take(maxLength)

    companion object {
        private const val MAX_FIELDS = 16

        internal fun create(
            ownerKey: String,
            name: String,
            config: KSLLoggerConfig,
            administratorMinimumLevel: () -> KSLLogLevel,
            maxMessageLength: () -> Int,
            sink: (KSLLogRecord) -> Unit
        ): KSLLogger {
            require(ownerKey.isNotBlank()) { "Logger owner ID must not be blank" }
            require(name.isNotBlank()) { "Logger name must not be blank" }
            return KSLLogger(ownerKey, name, config, administratorMinimumLevel, maxMessageLength, sink)
        }

        /** Fallback for alternate KSLAPI implementations; official KSL supplies admin-aware policy. */
        internal fun forJavaLogger(logger: Logger, ownerKey: String, name: String, config: KSLLoggerConfig): KSLLogger =
            create(ownerKey, name, config, { KSLLogLevel.INFO }, { 4_096 }) { record ->
                val rendered = record.render(4_096)
                when (record.level) {
                    KSLLogLevel.TRACE -> logger.info("[TRACE] $rendered")
                    KSLLogLevel.DEBUG -> logger.info("[DEBUG] $rendered")
                    KSLLogLevel.INFO -> logger.info(rendered)
                    KSLLogLevel.WARN -> if (record.cause == null) logger.warning(rendered) else logger.log(Level.WARNING, rendered, record.cause)
                    KSLLogLevel.ERROR -> if (record.cause == null) logger.severe(rendered) else logger.log(Level.SEVERE, rendered, record.cause)
                    KSLLogLevel.OFF -> Unit
                }
            }
    }
}
