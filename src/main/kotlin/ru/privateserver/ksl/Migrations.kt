package ru.privateserver.ksl

import java.sql.Connection

object Migrations {

    // One statement per schema version; the index is the version it brings the database to.
    fun statements(dialect: KSLDialect): List<String> {
        val sqlite = dialect == KSLDialect.SQLITE
        // MySQL cannot index plain TEXT, and its default collation is case-insensitive while SQLite's is not;
        // the binary collation keeps 'Foo' and 'foo' distinct on every backend.
        val key = if (sqlite) "TEXT" else "VARCHAR(190) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"
        val body = if (sqlite) "TEXT" else "LONGTEXT"
        val integer = if (sqlite) "INTEGER" else "BIGINT"
        val tail = if (sqlite) "" else " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
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
        val applied = version
        while (version < steps.size) {
            connection.createStatement().use { it.executeUpdate(steps[version]) }
            version++
            writeVersion(connection, dialect, version)
        }
        return version - applied
    }

    // SQLite keeps the version in its own header, which also keeps databases created by older KSL builds valid.
    // MySQL/MariaDB have no equivalent, so a small table holds it.
    private fun readVersion(connection: Connection, dialect: KSLDialect): Int {
        if (dialect == KSLDialect.SQLITE) {
            return connection.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
        connection.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ksl_meta (meta_key VARCHAR(64) NOT NULL PRIMARY KEY, meta_value VARCHAR(64)) " +
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
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
        connection.prepareStatement(dialect.upsert("ksl_meta", listOf("meta_key", "meta_value"), listOf("meta_key"))).use { ps ->
            ps.setString(1, SCHEMA_VERSION_KEY)
            ps.setString(2, version.toString())
            ps.executeUpdate()
        }
    }

    private const val SCHEMA_VERSION_KEY = "schema_version"
}
