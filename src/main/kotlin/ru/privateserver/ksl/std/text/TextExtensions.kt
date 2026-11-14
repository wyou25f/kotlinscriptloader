package ru.privateserver.ksl.std.text

import java.util.UUID

private val legacyColorCode = Regex("(?i)§[0-9A-FK-ORX]")
private val canonicalUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun Char.isKslWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

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
fun String.toUuidOrNull(): UUID? {
    val candidate = trim()
    if (!canonicalUuid.matches(candidate)) return null
    return runCatching { UUID.fromString(candidate) }.getOrNull()
}

/** True when this string is a valid UUID representation. */
fun String.isUuid(): Boolean = toUuidOrNull() != null

/** Splits text on Unicode whitespace (including non-breaking spaces) and drops empty entries. */
fun String.words(): List<String> {
    val result = mutableListOf<String>()
    var start = -1
    for (index in indices) {
        if (this[index].isKslWhitespace()) {
            if (start >= 0) {
                result += substring(start, index)
                start = -1
            }
        } else if (start < 0) {
            start = index
        }
    }
    if (start >= 0) result += substring(start)
    return result
}

/** Case-insensitive text checks that do not allocate a lowercase copy. */
fun String.containsIgnoreCase(other: String): Boolean = contains(other, ignoreCase = true)
fun String.startsWithIgnoreCase(prefix: String): Boolean = startsWith(prefix, ignoreCase = true)
fun String.endsWithIgnoreCase(suffix: String): Boolean = endsWith(suffix, ignoreCase = true)
fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) substring(0, length - suffix.length) else this
