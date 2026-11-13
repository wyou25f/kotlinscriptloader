package ru.privateserver.ksl.std.text

import java.util.UUID

private val legacyColorCode = Regex("(?i)§[0-9A-FK-ORX]")

/**
 * Truncates by Unicode code points, so a surrogate pair is never split.
 * [maxCodePoints] includes the suffix when truncation is needed.
 */
fun String.truncateCodePoints(maxCodePoints: Int, suffix: String = "…"): String {
    require(maxCodePoints >= 0) { "maxCodePoints must not be negative" }
    val length = codePointCount(0, this.length)
    if (length <= maxCodePoints) return this
    if (maxCodePoints == 0) return ""

    val suffixLength = suffix.codePointCount(0, suffix.length)
    if (suffixLength >= maxCodePoints) {
        return suffix.substring(0, suffix.offsetByCodePoints(0, maxCodePoints))
    }

    val prefixLength = maxCodePoints - suffixLength
    return substring(0, offsetByCodePoints(0, prefixLength)) + suffix
}

/** Removes legacy Bukkit color/style codes, including the §x§R§R§G§G§B§B hex form. */
fun String.stripLegacyColors(): String = replace(legacyColorCode, "")

/** Parses a UUID without throwing for user-provided input. */
fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(trim()) }.getOrNull()

/** True when this string is a valid UUID representation. */
fun String.isUuid(): Boolean = toUuidOrNull() != null

/** Splits text on one or more whitespace characters and drops empty entries. */
fun String.words(): List<String> = trim().split(Regex("\\s+")).filter(String::isNotEmpty)
