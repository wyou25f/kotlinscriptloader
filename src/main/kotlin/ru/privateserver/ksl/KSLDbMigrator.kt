package ru.privateserver.ksl

import java.io.File
import java.sql.Connection
import java.util.Properties

// Copies the data of the old SQLite file (ksl.db) into the configured MySQL/MariaDB database.
// Rows are upserted, so the migration can be repeated safely; the source file is never modified.
class KSLDbMigrator(private val plugin: KotlinScriptLoaderPlugin) {

    data class Counts(val persist: Int, val table: Int)

    private companion object {
        const val BATCH = 500
        // Must match the VARCHAR(190) key columns created by Migrations for MySQL/MariaDB.
        const val MAX_KEY_LENGTH = 190
    }

    private val sourceFile: File get() = File(plugin.dataFolder, "ksl.db")

    // Row counts in the SQLite file, or null when there is no file.
    fun inspect(): Counts? {
        if (!sourceFile.exists()) return null
        return openSource().use { Counts(count(it, "ksl_persist"), count(it, "ksl_table")) }
    }

    fun migrate(): Counts {
        check(plugin.dialect.isRemote) { "Перенос нужен только при database.type: mysql или mariadb" }
        check(sourceFile.exists()) { "Файл ${sourceFile.name} не найден" }
        return openSource().use { source ->
            plugin.database.connection.use { target ->
                target.autoCommit = false
                val persist = copy(source, target, "ksl_persist", listOf("script_name", "key", "value"), 2)
                val table = copy(source, target, "ksl_table", listOf("table_name", "row_key", "column_key", "value"), 3)
                Counts(persist, table)
            }
        }
    }

    // org.sqlite.JDBC is used directly: DriverManager only knows drivers that happened to be loaded,
    // and in MySQL mode the SQLite driver never was.
    private fun openSource(): Connection =
        org.sqlite.JDBC().connect("jdbc:sqlite:${sourceFile.absolutePath}", Properties())
            ?: error("SQLite-драйвер не открыл ${sourceFile.name}")

    private fun tableExists(source: Connection, table: String): Boolean =
        source.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { it.next() }
        }

    private fun count(source: Connection, table: String): Int {
        if (!tableExists(source, table)) return 0
        return source.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
    }

    // The first keyCount columns form the primary key. Committed per batch; a rerun overwrites the same rows.
    private fun copy(source: Connection, target: Connection, table: String, columns: List<String>, keyCount: Int): Int {
        if (!tableExists(source, table)) return 0
        val dialect = plugin.dialect
        val select = "SELECT ${columns.joinToString(", ") { dialect.quote(it) }} FROM ${dialect.quote(table)}"
        val insert = dialect.upsert(table, columns, columns.take(keyCount))
        var copied = 0
        var pending = 0
        source.createStatement().use { st ->
            st.executeQuery(select).use { rs ->
                target.prepareStatement(insert).use { ps ->
                    while (rs.next()) {
                        for (i in columns.indices) {
                            val value = rs.getString(i + 1)
                            // MySQL would cut or reject it with a cryptic error halfway through; fail early and say which row.
                            if (i < keyCount && (value?.length ?: 0) > MAX_KEY_LENGTH) {
                                error("$table: ${KSLError.DB_KEY_TOO_LONG.tag} ключ длиннее $MAX_KEY_LENGTH символов (${columns[i]} = '${value?.take(40)}…') — сократи его в ksl.db и повтори")
                            }
                            ps.setString(i + 1, value)
                        }
                        ps.addBatch()
                        copied++
                        if (++pending == BATCH) {
                            ps.executeBatch()
                            target.commit()
                            pending = 0
                        }
                    }
                    if (pending > 0) {
                        ps.executeBatch()
                        target.commit()
                    }
                }
            }
        }
        return copied
    }
}
