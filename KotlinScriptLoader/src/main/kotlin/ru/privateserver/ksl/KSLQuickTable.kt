package ru.privateserver.ksl

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable

class KSLTableStore(private val plugin: KotlinScriptLoaderPlugin) {

    private val cache = ConcurrentHashMap<String, KSLQuickTable>()

    fun table(name: String): KSLQuickTable =
        cache.computeIfAbsent(name) { KSLQuickTable(plugin, name) }
}

class KSLQuickTable(private val plugin: KotlinScriptLoaderPlugin, private val tableName: String) {

    fun set(rowKey: String, columnKey: String, value: Any?) {
        val encoded = KSLPersistCodec.encode(value) ?: run {
            plugin.logger.warning("[table:$tableName] Тип ${value?.let { it::class.simpleName }} не поддерживается — поддерживаются String, Int, Long, Double, Boolean, null")
            return
        }
        plugin.dbExecutor.submit {
            try {
                plugin.database.connection.use { conn: Connection ->
                    conn.prepareStatement(
                        "INSERT INTO ksl_table (table_name, row_key, column_key, value) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT(table_name, row_key, column_key) DO UPDATE SET value = excluded.value"
                    ).use { ps ->
                        ps.setString(1, tableName)
                        ps.setString(2, rowKey)
                        ps.setString(3, columnKey)
                        ps.setString(4, encoded)
                        ps.executeUpdate()
                    }
                }
            } catch (ex: Exception) {
                plugin.logger.warning("[table:$tableName] set('$rowKey', '$columnKey') не удался: ${ex.message}")
            }
        }
    }

    fun get(rowKey: String, columnKey: String): String? =
        (readRaw(rowKey, columnKey)?.let { KSLPersistCodec.decode(it) }) as? String

    fun getInt(rowKey: String, columnKey: String, default: Int = 0): Int =
        (readRaw(rowKey, columnKey)?.let { KSLPersistCodec.decode(it) }) as? Int ?: default

    fun getLong(rowKey: String, columnKey: String, default: Long = 0L): Long =
        (readRaw(rowKey, columnKey)?.let { KSLPersistCodec.decode(it) }) as? Long ?: default

    fun getDouble(rowKey: String, columnKey: String, default: Double = 0.0): Double =
        (readRaw(rowKey, columnKey)?.let { KSLPersistCodec.decode(it) }) as? Double ?: default

    fun getBoolean(rowKey: String, columnKey: String, default: Boolean = false): Boolean =
        (readRaw(rowKey, columnKey)?.let { KSLPersistCodec.decode(it) }) as? Boolean ?: default

    fun increment(rowKey: String, columnKey: String, amount: Long = 1L): Long {
        val task = Callable {
            var current = 0L
            try {
                plugin.database.connection.use { conn: Connection ->
                    conn.prepareStatement(
                        "SELECT value FROM ksl_table WHERE table_name = ? AND row_key = ? AND column_key = ?"
                    ).use { ps ->
                        ps.setString(1, tableName)
                        ps.setString(2, rowKey)
                        ps.setString(3, columnKey)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                current = (KSLPersistCodec.decode(rs.getString("value")) as? Long) ?: 0L
                            }
                        }
                    }
                    val updated = current + amount
                    conn.prepareStatement(
                        "INSERT INTO ksl_table (table_name, row_key, column_key, value) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT(table_name, row_key, column_key) DO UPDATE SET value = excluded.value"
                    ).use { ps ->
                        ps.setString(1, tableName)
                        ps.setString(2, rowKey)
                        ps.setString(3, columnKey)
                        ps.setString(4, KSLPersistCodec.encode(updated))
                        ps.executeUpdate()
                    }
                    current = updated
                }
            } catch (ex: Exception) {
                plugin.logger.warning("[table:$tableName] increment('$rowKey', '$columnKey') не удался: ${ex.message}")
            }
            current
        }
        return try {
            plugin.dbExecutor.submit(task).get()
        } catch (ex: Exception) {
            plugin.logger.warning("[table:$tableName] increment('$rowKey', '$columnKey') не удался: ${ex.message}")
            0L
        }
    }

    fun delete(rowKey: String, columnKey: String? = null) {
        plugin.dbExecutor.submit {
            try {
                plugin.database.connection.use { conn: Connection ->
                    val sql = if (columnKey != null)
                        "DELETE FROM ksl_table WHERE table_name = ? AND row_key = ? AND column_key = ?"
                    else
                        "DELETE FROM ksl_table WHERE table_name = ? AND row_key = ?"
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, tableName)
                        ps.setString(2, rowKey)
                        if (columnKey != null) ps.setString(3, columnKey)
                        ps.executeUpdate()
                    }
                }
            } catch (ex: Exception) {
                plugin.logger.warning("[table:$tableName] delete('$rowKey') не удался: ${ex.message}")
            }
        }
    }

    fun row(rowKey: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        try {
            plugin.database.connection.use { conn: Connection ->
                conn.prepareStatement(
                    "SELECT column_key, value FROM ksl_table WHERE table_name = ? AND row_key = ?"
                ).use { ps ->
                    ps.setString(1, tableName)
                    ps.setString(2, rowKey)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            result[rs.getString("column_key")] = KSLPersistCodec.decode(rs.getString("value"))
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("[table:$tableName] row('$rowKey') не удался: ${ex.message}")
        }
        return result
    }

    private fun readRaw(rowKey: String, columnKey: String): String? {
        return try {
            plugin.database.connection.use { conn: Connection ->
                conn.prepareStatement(
                    "SELECT value FROM ksl_table WHERE table_name = ? AND row_key = ? AND column_key = ?"
                ).use { ps ->
                    ps.setString(1, tableName)
                    ps.setString(2, rowKey)
                    ps.setString(3, columnKey)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
                }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("[table:$tableName] чтение '$rowKey'/'$columnKey' не удалось: ${ex.message}")
            null
        }
    }
}
