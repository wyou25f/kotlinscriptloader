package ru.privateserver.ksl

import java.sql.Connection

object Migrations {

    private val steps = listOf<(Connection) -> Unit>(
        { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS ksl_scripts (name TEXT PRIMARY KEY, last_loaded INTEGER)"
                )
            }
        },
        { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS ksl_persist (script_name TEXT NOT NULL, key TEXT NOT NULL, value TEXT, PRIMARY KEY (script_name, key))"
                )
            }
        },
        { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS ksl_table (table_name TEXT NOT NULL, row_key TEXT NOT NULL, column_key TEXT NOT NULL, value TEXT, PRIMARY KEY (table_name, row_key, column_key))"
                )
            }
        }
    )

    fun run(connection: Connection): Int {
        var version = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val applied = version
        while (version < steps.size) {
            steps[version](connection)
            version++
            connection.createStatement().use { it.executeUpdate("PRAGMA user_version = $version") }
        }
        return version - applied
    }
}
