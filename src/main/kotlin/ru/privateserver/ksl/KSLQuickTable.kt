package ru.privateserver.ksl

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

class KSLTableStore(private val plugin: KotlinScriptLoaderPlugin) {

    private val cache = ConcurrentHashMap<String, KSLQuickTable>()

    fun table(name: String): KSLQuickTable =
        cache.computeIfAbsent(name) { KSLQuickTable(plugin, name) }
}

class KSLQuickTable(private val plugin: KotlinScriptLoaderPlugin, private val tableName: String) {

    fun set(rowKey: String, columnKey: String, value: Any?) {
        val encoded = KSLPersistCodec.encode(value) ?: throw IllegalArgumentException(
            "[table:$tableName] Тип ${value?.let { it::class.simpleName }} не поддерживается — " +
                "поддерживаются String, Int, Long, Double, Boolean, null"
        )
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

    // NOTE: increment() and all reads wait for the DB queue so they observe earlier set()/delete() calls;
    // that can block the caller while a large backlog drains.
    fun increment(rowKey: String, columnKey: String, amount: Long = 1L): Long {
        val task: () -> Long = {
            plugin.database.connection.use { conn: Connection ->
                val current = conn.prepareStatement(
                    "SELECT value FROM ksl_table WHERE table_name = ? AND row_key = ? AND column_key = ?"
                ).use { ps ->
                    ps.setString(1, tableName)
                    ps.setString(2, rowKey)
                    ps.setString(3, columnKey)
                    ps.executeQuery().use { rs ->
                        // Int is valid too: set(.., 5) stores 'i:5'; treating it as 0 would wipe the counter.
                        when (val stored = if (rs.next()) KSLPersistCodec.decode(rs.getString("value")) else null) {
                            null -> 0L
                            is Long -> stored
                            is Int -> stored.toLong()
                            else -> throw IllegalStateException("хранится ${stored::class.simpleName}, а не целое число")
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
                updated
            }
        }
        // A fake 0 on failure looks like a reset counter to the script; surface the error instead.
        return try {
            plugin.dbCall(task)
        } catch (ex: Exception) {
            throw IllegalStateException("[table:$tableName] increment('$rowKey', '$columnKey') не удался: ${ex.message}", ex)
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

    fun row(rowKey: String): Map<String, Any?> = try {
        plugin.dbCall {
            val result = mutableMapOf<String, Any?>()
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
            result
        }
    } catch (ex: Exception) {
        plugin.logger.warning("[table:$tableName] row('$rowKey') не удался: ${ex.message}")
        emptyMap()
    }

    private fun readRaw(rowKey: String, columnKey: String): String? = try {
        plugin.dbCall {
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
        }
    } catch (ex: Exception) {
        plugin.logger.warning("[table:$tableName] чтение '$rowKey'/'$columnKey' не удалось: ${ex.message}")
        null
    }
}
