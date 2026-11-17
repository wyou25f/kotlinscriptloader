package ru.privateserver.ksl

import java.sql.Connection
import java.sql.ResultSet

/**
 * A transaction-scoped JDBC facade.
 *
 * It exposes parameter binding and detached row mapping, not the raw Connection or
 * ResultSet. Instances are created by [BukkitScriptContext.dbTransactionAsync].
 */
class KSLDatabaseSession internal constructor(
    private val connection: Connection,
    private val policy: KSLDatabaseExecutionPolicy,
    private val isActive: () -> Boolean = { true }
) {
    private fun checkActive() {
        check(isActive()) { "The owning KSL script runtime was unloaded or reloaded" }
    }

    /** Executes a parameterized INSERT/UPDATE/DELETE/DDL statement. */
    fun execute(sql: String, vararg parameters: Any?): Int {
        checkActive()
        return policy.prepare(connection, sql, parameters, query = false).use { statement ->
            checkActive()
            statement.executeUpdate()
        }
    }

    /** Maps at most one row. The mapper is invoked only while its ResultSet is open. */
    fun <T> queryOne(
        sql: String,
        vararg parameters: Any?,
        mapper: ResultSet.() -> T
    ): T? {
        checkActive()
        val statement = policy.prepare(connection, sql, parameters, query = true)
        statement.maxRows = 1
        return statement.use { ps ->
            ps.executeQuery().use { rows ->
                checkActive()
                if (rows.next()) mapper(rows) else null
            }
        }
    }

    /**
     * Maps rows into detached values. The configured row cap is applied by JDBC;
     * do not return ResultSet objects or other JDBC resources from [mapper].
     */
    fun <T> queryList(
        sql: String,
        vararg parameters: Any?,
        mapper: ResultSet.() -> T
    ): List<T> {
        checkActive()
        return policy.prepare(connection, sql, parameters, query = true).use { statement ->
            statement.executeQuery().use { rows ->
                val result = ArrayList<T>()
                while (rows.next()) {
                    checkActive()
                    result.add(mapper(rows))
                }
                result
            }
        }
    }

    /**
     * Executes a bounded prepared batch. Each row must provide the same parameter
     * count. The returned update counts follow JDBC's executeBatch contract.
     */
    fun batch(sql: String, rows: Iterable<List<Any?>>): IntArray {
        checkActive()
        policy.validateSql(sql)
        val materialized = rows.take(policy.maxBatchRows + 1)
        policy.validateBatchSize(materialized.size)
        val parameterCount = materialized.first().size
        require(materialized.all { it.size == parameterCount }) {
            "Every SQL batch row must have the same number of parameters"
        }

        return connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = policy.queryTimeoutSeconds
            materialized.forEach { values ->
                checkActive()
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.addBatch()
            }
            checkActive()
            statement.executeBatch()
        }
    }
}
