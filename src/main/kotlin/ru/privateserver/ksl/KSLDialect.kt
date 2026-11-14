package ru.privateserver.ksl

import java.util.Locale

// The few places where SQLite and MySQL/MariaDB disagree. Everything else in KSL sticks to portable SQL.
enum class KSLDialect(val label: String) {
    SQLITE("SQLite"),
    MYSQL("MySQL"),
    MARIADB("MariaDB");

    val isRemote: Boolean get() = this != SQLITE

    // Backticks are native in MySQL/MariaDB and accepted by SQLite. Needed because "key" is a reserved word there.
    fun quote(identifier: String): String {
        require(IDENTIFIER.matches(identifier)) { "Invalid SQL identifier: '$identifier'" }
        return "`$identifier`"
    }

    fun requireKeyLength(field: String, value: String) {
        if (isRemote) require(value.length <= MAX_KEY_LENGTH) {
            "$field exceeds $MAX_KEY_LENGTH characters for $label"
        }
    }

    // INSERT that updates every non-key column when the key already exists.
    fun upsert(table: String, columns: List<String>, keys: List<String>): String {
        require(keys.isNotEmpty() && columns.containsAll(keys)) { "upsert: ключи должны входить в список колонок" }
        val updated = columns.filter { it !in keys }
        require(updated.isNotEmpty()) { "upsert: нет колонок для обновления" }

        val head = "INSERT INTO ${quote(table)} (${columns.joinToString(", ") { quote(it) }}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"
        return if (this == SQLITE) {
            val conflict = keys.joinToString(", ") { quote(it) }
            val assignments = updated.joinToString(", ") { "${quote(it)} = excluded.${quote(it)}" }
            "$head ON CONFLICT($conflict) DO UPDATE SET $assignments"
        } else {
            val assignments = updated.joinToString(", ") { "${quote(it)} = VALUES(${quote(it)})" }
            "$head ON DUPLICATE KEY UPDATE $assignments"
        }
    }

    companion object {
        private const val MAX_KEY_LENGTH = 190
        private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        fun parse(name: String): KSLDialect? = when (name.trim().lowercase(Locale.ROOT)) {
            "sqlite" -> SQLITE
            "mysql" -> MYSQL
            "mariadb", "maria" -> MARIADB
            else -> null
        }
    }
}
