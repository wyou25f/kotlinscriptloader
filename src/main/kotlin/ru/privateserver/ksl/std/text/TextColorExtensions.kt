package ru.privateserver.ksl.std.text

import java.util.Locale

private val expandedHexColor = Regex("(?i)[&§]x(?:[&§][0-9a-f]){6}")
private val simpleHexColor = Regex("(?i)[&§]#([0-9a-f]{6})")
private val legacyColor = Regex("(?i)[&§]([0-9a-fk-or])")

private val miniMessageLegacyTags = mapOf(
    '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua",
    '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray",
    '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua",
    'c' to "red", 'd' to "light_purple", 'e' to "yellow", 'f' to "white",
    'k' to "obfuscated", 'l' to "bold", 'm' to "strikethrough",
    'n' to "underlined", 'o' to "italic", 'r' to "reset"
)

/** Converts Bukkit/Spigot legacy colors (&a, §l, &#RRGGBB and &x hex) into MiniMessage tags. */
fun String.toMiniMessageMarkup(): String {
    val expanded = expandedHexColor.replace(this) { match ->
        val hex = match.value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        "<#" + hex + ">"
    }
    val simple = simpleHexColor.replace(expanded) { match -> "<#" + match.groupValues[1] + ">" }
    return legacyColor.replace(simple) { match ->
        val code = match.groupValues[1].lowercase(Locale.ROOT).first()
        "<" + miniMessageLegacyTags.getValue(code) + ">"
    }
}

/** Converts supported legacy colors to section-sign formatting for Bukkit String APIs. */
fun String.colorizeLegacy(): String {
    // Translate single-character codes before expanding hex; otherwise §A in a hex color
    // would be mistaken for the legacy green code and its original hex case would be lost.
    val legacy = legacyColor.replace(this) { match ->
        "§" + match.groupValues[1].lowercase(Locale.ROOT)
    }
    val simple = simpleHexColor.replace(legacy) { match ->
        val hex = match.groupValues[1]
        "§x" + hex.map { "§" + it }.joinToString("")
    }
    return expandedHexColor.replace(simple) { match -> match.value.replace('&', '§') }
}

/** Short alias for [colorizeLegacy]. */
fun String.colors(): String = colorizeLegacy()
/** Short alias for [toMiniMessageMarkup]. */
fun String.mmMarkup(): String = toMiniMessageMarkup()