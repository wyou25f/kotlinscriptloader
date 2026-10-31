package ru.privateserver.ksl

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

class KSLTableStore(private val plugin: KotlinScriptLoaderPlugin) {

    private val cache = ConcurrentHashMap<String, KSLQuickTable>()

    fun table(name: String): KSLQuickTable =
        cache.computeIfAbsent(name) { KSLQuickTable(plugin, name) }

    // After /ksl reload: values may have been edited in the database by hand (phpMyAdmin, DB Browser).
    fun clearCaches() = cache.values.forEach { it.clearValueCache() }
}

class KSLQuickTable(private val plugin: KotlinScriptLoaderPlugin, private val tableName: String) {

    // The dialect is fixed once the database is initialised, so the statement is built once per table.
    private val upsertSql by lazy {
        plugin.dialect.upsert("ksl_table", listOf("table_name", "row_key", "column_key", "value"), listOf("table_name", "row_key", "column_key"))
    }

    // Values read from this table stay in memory (SQLite by default, off for MySQL where other servers may write).
    private val valueCache: KSLValueCache? by lazy { if (plugin.tableCacheEnabled) KSLValueCache(10_000) else null }

    private fun cacheKey(rowKey: String, columnKey: String) = "$rowKey\u0000$columnKey"

    fun clearValueCache() { valueCache?.clear() }

    fun set(rowKey: String, columnKey: String, value: Any?) {
        val encoded = KSLPersistCodec.encode(value) ?: throw IllegalArgumentException(
            "[table:$tableName] Тип ${value?.let { it::class.simpleName }} ${KSLError.DB_UNSUPPORTED_TYPE.tag} не поддерживается — " +
                "поддерживаются String, Int, Long, Double, Boolean, null"
        )
        // Write-through: a read right after this set() sees the new value without waiting for the queue.
        valueCache?.store(cacheKey(rowKey, columnKey), encoded)
        plugin.dbExecutor.submit {
            try {
                plugin.database.connection.use { conn: Connection ->
                    conn.prepareStatement(
                        upsertSql
                    ).use { ps ->
                        ps.setString(1, tableName)
                        ps.setString(2, rowKey)
                        ps.setString(3, columnKey)
                        ps.setString(4, encoded)
                        ps.executeUpdate()
                    }
                }
            } catch (ex: Exception) {
                plugin.logger.warning("[table:$tableName] ${KSLError.DB_TABLE_FAILED.tag} set('$rowKey', '$columnKey') не удался: ${ex.message}")
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
                            else -> throw IllegalStateException("${KSLError.DB_INCREMENT_FAILED.tag} хранится ${stored::class.simpleName}, а не целое число")
                        }
                    }
                }
                val updated = current + amount
                conn.prepareStatement(
                    upsertSql
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
            plugin.dbCall(task).also { valueCache?.store(cacheKey(rowKey, columnKey), KSLPersistCodec.encode(it)) }
        } catch (ex: Exception) {
            throw IllegalStateException("[table:$tableName] ${KSLError.DB_INCREMENT_FAILED.tag} increment('$rowKey', '$columnKey') не удался: ${ex.message}", ex)
        }
    }

    fun delete(rowKey: String, columnKey: String? = null) {
        // Deleting a whole row would need an index of its columns; emptying the cache is rare and simpler.
        valueCache?.clear()
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
                plugin.logger.warning("[table:$tableName] ${KSLError.DB_TABLE_FAILED.tag} delete('$rowKey') не удался: ${ex.message}")
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
        plugin.logger.warning("[table:$tableName] ${KSLError.DB_TABLE_FAILED.tag} row('$rowKey') не удался: ${ex.message}")
        emptyMap()
    }

    private fun readRaw(rowKey: String, columnKey: String): String? {
        val cache = valueCache ?: return readRawFromDb(rowKey, columnKey)
        val key = cacheKey(rowKey, columnKey)
        if (cache.has(key)) return cache.value(key)
        val seen = cache.version()
        val raw = readRawFromDb(rowKey, columnKey)
        cache.fill(key, raw, seen)
        return raw
    }

    private fun readRawFromDb(rowKey: String, columnKey: String): String? = try {
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
        plugin.logger.warning("[table:$tableName] ${KSLError.DB_TABLE_FAILED.tag} чтение '$rowKey'/'$columnKey' не удалось: ${ex.message}")
        null
    }
}
