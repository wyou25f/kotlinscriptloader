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
    // Protect complete hex sequences while translating ordinary one-character codes.
    val hexPattern = Regex("(?i)(?:[&§]x(?:[&§][0-9a-f]){6}|[&§]#[0-9a-f]{6})")
    val result = StringBuilder(length + 16)
    var cursor = 0
    hexPattern.findAll(this).forEach { match ->
        val before = substring(cursor, match.range.first)
        result.append(legacyColor.replace(before) { code ->
            "§" + code.groupValues[1].lowercase(Locale.ROOT)
        })
        val token = match.value
        if (token.startsWith("&#") || token.startsWith("§#")) {
            val hex = token.substring(2)
            result.append("§x").append(hex.map { "§" + it }.joinToString(""))
        } else {
            result.append(token.replace('&', '§'))
        }
        cursor = match.range.last + 1
    }
    val tail = substring(cursor)
    result.append(legacyColor.replace(tail) { code ->
        "§" + code.groupValues[1].lowercase(Locale.ROOT)
    })
    return result.toString()
}

/** Short alias for [colorizeLegacy]. */
fun String.colors(): String = colorizeLegacy()
/** Short alias for [toMiniMessageMarkup]. */
fun String.mmMarkup(): String = toMiniMessageMarkup()