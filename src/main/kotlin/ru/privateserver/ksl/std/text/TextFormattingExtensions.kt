package ru.privateserver.ksl.std.text

import java.util.Locale

/** Collapses all whitespace runs into one ordinary space and trims the ends. */
fun String.normalizeWhitespace(): String = words().joinToString(" ")

/**
 * Converts common camelCase, PascalCase, whitespace and hyphen-separated names
 * to snake_case. Existing underscores are preserved.
 */
fun String.toSnakeCase(): String = normalizeWhitespace()
    .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
    .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    .replace(Regex("[\\s-]+"), "_")
    .lowercase(Locale.ROOT)

/** Converts underscore-, hyphen- or whitespace-separated words to lower camelCase. */
fun String.toCamelCase(): String {
    val parts = normalizeWhitespace().split(Regex("[\\s_-]+")).filter(String::isNotEmpty)
    if (parts.isEmpty()) return ""
    return parts.first().lowercase(Locale.ROOT) + parts.drop(1).joinToString("") { part ->
        part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

/** Checks the username character and length rules used by vanilla Minecraft accounts. */
fun String.isMinecraftUsername(): Boolean = matches(Regex("[A-Za-z0-9_]{3,16}"))
