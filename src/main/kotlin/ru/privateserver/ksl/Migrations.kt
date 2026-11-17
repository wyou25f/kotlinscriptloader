package ru.privateserver.ksl

import java.sql.Connection

object Migrations {

    // One statement per schema version; the index is the version it brings the database to.
    fun statements(dialect: KSLDialect): List<String> {
        val mysql = dialect.isMySqlFamily
        val sqlite = dialect == KSLDialect.SQLITE
        val key = when {
            sqlite -> "TEXT"
            mysql -> "VARCHAR(190) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"
            else -> "VARCHAR(190)"
        }
        val body = when {
            sqlite -> "TEXT"
            mysql -> "LONGTEXT"
            else -> "TEXT"
        }
        val integer = if (sqlite) "INTEGER" else "BIGINT"
        val tail = if (mysql) " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" else ""
        val k = dialect.quote("key")
        return listOf(
            "CREATE TABLE IF NOT EXISTS ksl_scripts (name $key PRIMARY KEY, last_loaded $integer)$tail",
            "CREATE TABLE IF NOT EXISTS ksl_persist (script_name $key NOT NULL, $k $key NOT NULL, value $body, PRIMARY KEY (script_name, $k))$tail",
            "CREATE TABLE IF NOT EXISTS ksl_table (table_name $key NOT NULL, row_key $key NOT NULL, column_key $key NOT NULL, value $body, PRIMARY KEY (table_name, row_key, column_key))$tail"
        )
    }

    fun run(connection: Connection, dialect: KSLDialect): Int {
        val steps = statements(dialect)
        var version = readVersion(connection, dialect)
        check(version in 0..steps.size) {
            "${KSLError.DB_SCHEMA_VERSION_NEWER.tag} Схема БД имеет версию $version, а эта версия KSL поддерживает только 0..${steps.size}. " +
                "Обнови KSL; не меняй номер версии вручную."
        }
        val applied = version
        while (version < steps.size) {
            connection.createStatement().use { it.executeUpdate(steps[version]) }
            version++
            writeVersion(connection, dialect, version)
        }
        return version - applied
    }

    // SQLite keeps the version in its own header; remote backends use ksl_meta.
    private fun readVersion(connection: Connection, dialect: KSLDialect): Int {
        if (dialect == KSLDialect.SQLITE) {
            return connection.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
        val metaTail = if (dialect.isMySqlFamily) " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" else ""
        connection.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ksl_meta (meta_key VARCHAR(64) NOT NULL PRIMARY KEY, meta_value VARCHAR(64))$metaTail"
            )
        }
        return connection.prepareStatement("SELECT meta_value FROM ksl_meta WHERE meta_key = ?").use { ps ->
            ps.setString(1, SCHEMA_VERSION_KEY)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.toIntOrNull() ?: 0 else 0 }
        }
    }

    private fun writeVersion(connection: Connection, dialect: KSLDialect, version: Int) {
        if (dialect == KSLDialect.SQLITE) {
            connection.createStatement().use { it.executeUpdate("PRAGMA user_version = $version") }
            return
        }
        connection.prepareStatement(
            dialect.upsert("ksl_meta", listOf("meta_key", "meta_value"), listOf("meta_key"))
        ).use { ps ->
            ps.setString(1, SCHEMA_VERSION_KEY)
            ps.setString(2, version.toString())
            ps.executeUpdate()
        }
    }

    private const val SCHEMA_VERSION_KEY = "schema_version"
}
