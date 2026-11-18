package ru.privateserver.ksl

import java.util.Locale

/** SQL differences supported by KSL's internal stores and migration engine. */
enum class KSLDialect(val label: String) {
    SQLITE("SQLite"),
    MYSQL("MySQL"),
    MARIADB("MariaDB"),
    POSTGRESQL("PostgreSQL");

    val isRemote: Boolean get() = this != SQLITE
    val isMySqlFamily: Boolean get() = this == MYSQL || this == MARIADB

    /** Uses the identifier quoting style native to the selected backend. */
    fun quote(identifier: String): String {
        require(IDENTIFIER.matches(identifier)) { "Invalid SQL identifier: '$identifier'" }
        return if (isMySqlFamily) "`$identifier`" else "\"$identifier\""
    }

    fun requireKeyLength(field: String, value: String) {
        if (isRemote) require(value.length <= MAX_KEY_LENGTH) {
            "$field exceeds $MAX_KEY_LENGTH characters for $label"
        }
    }

    /** INSERT that updates every non-key column when the key already exists. */
    fun upsert(table: String, columns: List<String>, keys: List<String>): String {
        require(keys.isNotEmpty() && columns.containsAll(keys)) {
            "upsert: ключи должны входить в список колонок"
        }
        require(columns.distinct().size == columns.size) { "upsert: имена колонок должны быть уникальны" }
        val updated = columns.filter { it !in keys }
        require(updated.isNotEmpty()) { "upsert: нет колонок для обновления" }

        val head = "INSERT INTO ${quote(table)} (${columns.joinToString(", ") { quote(it) }}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"
        return when {
            this == SQLITE -> {
                val conflict = keys.joinToString(", ") { quote(it) }
                val assignments = updated.joinToString(", ") {
                    "${quote(it)} = excluded.${quote(it)}"
                }
                "$head ON CONFLICT($conflict) DO UPDATE SET $assignments"
            }
            this == POSTGRESQL -> {
                val conflict = keys.joinToString(", ") { quote(it) }
                val assignments = updated.joinToString(", ") {
                    "${quote(it)} = EXCLUDED.${quote(it)}"
                }
                "$head ON CONFLICT($conflict) DO UPDATE SET $assignments"
            }
            else -> {
                val assignments = updated.joinToString(", ") {
                    "${quote(it)} = VALUES(${quote(it)})"
                }
                "$head ON DUPLICATE KEY UPDATE $assignments"
            }
        }
    }

    companion object {
        private const val MAX_KEY_LENGTH = 190
        private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun parse(name: String): KSLDialect? = when (name.trim().lowercase(Locale.ROOT)) {
            "sqlite" -> SQLITE
            "mysql" -> MYSQL
            "mariadb", "maria" -> MARIADB
            "postgres", "postgresql", "pgsql" -> POSTGRESQL
            else -> null
        }
    }
}
