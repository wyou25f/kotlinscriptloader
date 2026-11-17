package ru.privateserver.ksl

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException

class KSLTableStore(private val plugin: KotlinScriptLoaderPlugin) {

    private val cache = ConcurrentHashMap<String, KSLQuickTable>()
    private val valueCaches = ConcurrentHashMap<String, KSLValueCache>()

    private fun sharedValueCache(name: String): KSLValueCache? =
        if (plugin.tableCacheEnabled) valueCaches.computeIfAbsent(name) { KSLValueCache(10_000) } else null

    fun table(name: String): KSLQuickTable =
        cache.computeIfAbsent(name) { KSLQuickTable.shared(plugin, it, sharedValueCache(it)) }

    /** Creates a script-owned table view that shares data cache but scopes queued writes to one runtime. */
    fun scopedTable(
        name: String,
        ownerName: String,
        runtimeScope: ru.privateserver.ksl.rt.KSLRuntimeScope
    ): KSLQuickTable = KSLQuickTable.scoped(plugin, name, ownerName, runtimeScope, sharedValueCache(name))

    // After /ksl reload: values may have been edited in the database by hand (phpMyAdmin, DB Browser).
    fun clearCaches() {
        cache.values.forEach { it.clearValueCache() }
        valueCaches.values.forEach { it.clear() }
    }
}

class KSLQuickTable private constructor(
    private val plugin: KotlinScriptLoaderPlugin,
    private val tableName: String,
    private val ownerName: String?,
    private val runtimeScope: ru.privateserver.ksl.rt.KSLRuntimeScope?,
    sharedValueCache: KSLValueCache?
) {
    constructor(plugin: KotlinScriptLoaderPlugin, tableName: String) :
        this(plugin, tableName, null, null, null)

    companion object {
        internal fun shared(
            plugin: KotlinScriptLoaderPlugin,
            tableName: String,
            valueCache: KSLValueCache?
        ): KSLQuickTable = KSLQuickTable(plugin, tableName, null, null, valueCache)

        internal fun scoped(
            plugin: KotlinScriptLoaderPlugin,
            tableName: String,
            ownerName: String,
            runtimeScope: ru.privateserver.ksl.rt.KSLRuntimeScope,
            valueCache: KSLValueCache?
        ): KSLQuickTable = KSLQuickTable(plugin, tableName, ownerName, runtimeScope, valueCache)
    }

    private fun isActive(): Boolean = runtimeScope?.isClosed != true

    private fun ensureActive() {
        check(isActive()) {
            "KSL table '$tableName' belongs to unloaded script runtime '${ownerName ?: "unknown"}'"
        }
        ownerName?.let(plugin::requireScriptDatabaseAccess)
    }

    init { plugin.dialect.requireKeyLength("table_name", tableName) }

    private fun validateKeys(rowKey: String, columnKey: String? = null) {
        plugin.dialect.requireKeyLength("row_key", rowKey)
        columnKey?.let { plugin.dialect.requireKeyLength("column_key", it) }
    }

    // The dialect is fixed once the database is initialised, so the statement is built once per table.
    private val upsertSql by lazy {
        plugin.dialect.upsert("ksl_table", listOf("table_name", "row_key", "column_key", "value"), listOf("table_name", "row_key", "column_key"))
    }

    // Values read from this table stay in memory (SQLite by default, off for MySQL where other servers may write).
    private val valueCache: KSLValueCache? =
        sharedValueCache ?: if (runtimeScope == null && plugin.tableCacheEnabled) KSLValueCache(10_000) else null

    private fun cacheKey(rowKey: String, columnKey: String) = "$rowKey\u0000$columnKey"

    fun clearValueCache() { valueCache?.clear() }

    // Repeated set() of the same cell while an earlier write is still queued becomes one write of the newest value.
    private val writes = KSLWriteCoalescer()
    private val operationGate = KSLTableOperationGate()

    fun set(rowKey: String, columnKey: String, value: Any?) {
        ensureActive()
        validateKeys(rowKey, columnKey)
        val encoded = KSLPersistCodec.encode(value) ?: throw IllegalArgumentException(
            "[table:$tableName] Тип ${value?.let { it::class.simpleName }} ${KSLError.DB_UNSUPPORTED_TYPE.tag} не поддерживается — " +
                "поддерживаются String, Int, Long, Double, Boolean, null"
        )
        operationGate.withLock {
            // Write-through: a read right after this set() sees the new value without waiting for the queue.
            valueCache?.store(cacheKey(rowKey, columnKey), encoded)
            // null: a write for this cell is already queued and will pick the newest value up.
            val ticket = writes.offer(cacheKey(rowKey, columnKey), encoded) ?: return@withLock
            val writeTask = Runnable {
                val taken = writes.take(ticket) ?: return@Runnable
                if (!isActive()) return@Runnable
                try {
                    ensureActive()
                    plugin.database.connection.use { conn: Connection ->
                        conn.prepareStatement(upsertSql).use { ps ->
                            ps.setString(1, tableName)
                            ps.setString(2, rowKey)
                            ps.setString(3, columnKey)
                            ps.setString(4, taken.value)
                            ps.queryTimeout = plugin.databaseExecutionPolicy.queryTimeoutSeconds
                            ps.executeUpdate()
                        }
                    }
                } catch (ex: Exception) {
                    valueCache?.invalidate(cacheKey(rowKey, columnKey))
                    val code = KSLErrors.classifyDatabaseFailure(ex, KSLError.DB_TABLE_FAILED)
                    plugin.logger.warning("[table:$tableName] ${code.tag} set('$rowKey', '$columnKey') не удался: ${ex.message}")
                }
            }
            if (runtimeScope != null) {
                runtimeScope.submit(plugin.dbExecutor) { writeTask.run(); Unit }.whenComplete { _, error ->
                    if (error != null) {
                        // Cancel/rejection must release the coalescer ticket so the cache cannot wedge.
                        writes.take(ticket)
                        valueCache?.invalidate(cacheKey(rowKey, columnKey))
                        if (error !is java.util.concurrent.CancellationException) {
                            plugin.logger.warning("[table:$tableName] ${KSLError.DB_QUEUE_LARGE.tag} Scoped write was rejected: ${error.cause?.message ?: error.message}")
                        }
                    }
                }
            } else try {
                plugin.dbExecutor.execute(writeTask)
            } catch (ex: RejectedExecutionException) {
                // Remove the pending marker so a subsequent set() can enqueue instead of being coalesced forever.
                writes.take(ticket)
                valueCache?.invalidate(cacheKey(rowKey, columnKey))
                plugin.logger.warning("[table:$tableName] ${KSLError.DB_QUEUE_LARGE.tag} Очередь БД заполнена: '$rowKey'/'$columnKey' — запись отклонена")
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
        ensureActive()
        validateKeys(rowKey, columnKey)
        val task: () -> Long = {
            ensureActive()
            plugin.database.connection.use { conn: Connection ->
                val current = conn.prepareStatement(
                    "SELECT value FROM ksl_table WHERE table_name = ? AND row_key = ? AND column_key = ?"
                ).use { ps ->
                    ps.setString(1, tableName)
                    ps.setString(2, rowKey)
                    ps.setString(3, columnKey)
                    ps.queryTimeout = plugin.databaseExecutionPolicy.queryTimeoutSeconds
                    ps.maxRows = 1
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
                ensureActive()
                val updated = Math.addExact(current, amount)
                conn.prepareStatement(
                    upsertSql
                ).use { ps ->
                    ps.setString(1, tableName)
                    ps.setString(2, rowKey)
                    ps.setString(3, columnKey)
                    ps.setString(4, KSLPersistCodec.encode(updated))
                    ps.queryTimeout = plugin.databaseExecutionPolicy.queryTimeoutSeconds
                    ps.executeUpdate()
                }
                updated
            }
        }
        // A fake 0 on failure looks like a reset counter to the script; surface the error instead.
        return operationGate.withLock {
            writes.barrier()
            try {
                plugin.dbCall(task).also { valueCache?.store(cacheKey(rowKey, columnKey), KSLPersistCodec.encode(it)) }
            } catch (ex: Exception) {
                val code = KSLErrors.classifyDatabaseFailure(ex, KSLError.DB_INCREMENT_FAILED)
                throw IllegalStateException("[table:$tableName] ${code.tag} increment('$rowKey', '$columnKey') не удался: ${ex.message}", ex)
            }
        }
    }

    fun delete(rowKey: String, columnKey: String? = null) {
        ensureActive()
        validateKeys(rowKey, columnKey)
        operationGate.withLock {
            // Deleting a whole row would need an index of its columns; emptying the cache is rare and simpler.
            valueCache?.clear()
            // Writes queued before the delete must not be merged with ones made after it.
            writes.barrier()
            val deleteTask = Runnable {
                if (!isActive()) return@Runnable
                try {
                    ensureActive()
                    plugin.database.connection.use { conn: Connection ->
                        val sql = if (columnKey != null)
                            "DELETE FROM ksl_table WHERE table_name = ? AND row_key = ? AND column_key = ?"
                        else
                            "DELETE FROM ksl_table WHERE table_name = ? AND row_key = ?"
                        conn.prepareStatement(sql).use { ps ->
                            ps.setString(1, tableName)
                            ps.setString(2, rowKey)
                            if (columnKey != null) ps.setString(3, columnKey)
                            ps.queryTimeout = plugin.databaseExecutionPolicy.queryTimeoutSeconds
                            ps.executeUpdate()
                        }
                    }
                } catch (ex: Exception) {
                    val code = KSLErrors.classifyDatabaseFailure(ex, KSLError.DB_TABLE_FAILED)
                    plugin.logger.warning("[table:$tableName] ${code.tag} delete('$rowKey') не удался: ${ex.message}")
                }
            }
            if (runtimeScope != null) {
                runtimeScope.submit(plugin.dbExecutor) { deleteTask.run(); Unit }.whenComplete { _, error ->
                    if (error != null) valueCache?.clear()
                    if (error != null && error !is java.util.concurrent.CancellationException) {
                        plugin.logger.warning("[table:$tableName] ${KSLError.DB_QUEUE_LARGE.tag} Scoped delete failed: ${error.cause?.message ?: error.message}")
                    }
                }
            } else try {
                plugin.dbExecutor.execute(deleteTask)
            } catch (ex: RejectedExecutionException) {
                plugin.logger.warning("[table:$tableName] ${KSLError.DB_QUEUE_LARGE.tag} Очередь БД заполнена: delete('$rowKey') не поставлен в очередь")
            }
        }
    }

    fun row(rowKey: String): Map<String, Any?> {
        ensureActive()
        validateKeys(rowKey)
        return operationGate.withLock {
            try {
                plugin.dbCall {
                    ensureActive()
                    val result = mutableMapOf<String, Any?>()
                    plugin.database.connection.use { conn: Connection ->
                        conn.prepareStatement(
                            "SELECT column_key, value FROM ksl_table WHERE table_name = ? AND row_key = ?"
                        ).use { ps ->
                            ps.setString(1, tableName)
                            ps.setString(2, rowKey)
                            ps.queryTimeout = plugin.databaseExecutionPolicy.queryTimeoutSeconds
                            ps.maxRows = plugin.databaseExecutionPolicy.maxQueryRows
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
                val code = KSLErrors.classifyDatabaseFailure(ex, KSLError.DB_TABLE_FAILED)
                plugin.logger.warning("[table:$tableName] ${code.tag} row('$rowKey') не удался: ${ex.message}")
                emptyMap()
            }
        }
    }

    private fun readRaw(rowKey: String, columnKey: String): String? {
        ensureActive()
        validateKeys(rowKey, columnKey)
        val cache = valueCache ?: return readRawFromDb(rowKey, columnKey)
        val key = cacheKey(rowKey, columnKey)
        if (cache.has(key)) return cache.value(key)
        val seen = cache.version()
        val raw = readRawFromDb(rowKey, columnKey)
        cache.fill(key, raw, seen)
        return raw
    }

    private fun readRawFromDb(rowKey: String, columnKey: String): String? = operationGate.withLock {
        ensureActive()
        try {
            plugin.dbCall {
                ensureActive()
                plugin.database.connection.use { conn: Connection ->
                    conn.prepareStatement(
                        "SELECT value FROM ksl_table WHERE table_name = ? AND row_key = ? AND column_key = ?"
                    ).use { ps ->
                        ps.setString(1, tableName)
                        ps.setString(2, rowKey)
                        ps.setString(3, columnKey)
                        ps.queryTimeout = plugin.databaseExecutionPolicy.queryTimeoutSeconds
                        ps.maxRows = 1
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
                    }
                }
            }
        } catch (ex: Exception) {
            val code = KSLErrors.classifyDatabaseFailure(ex, KSLError.DB_TABLE_FAILED)
            plugin.logger.warning("[table:$tableName] ${code.tag} чтение '$rowKey'/'$columnKey' не удалось: ${ex.message}")
            null
        }
    }
}
