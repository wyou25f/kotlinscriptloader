package ru.privateserver.ksl

import java.sql.Connection
import java.sql.PreparedStatement

/**
 * Bounded execution policy for script-issued SQL.
 * Values are clamped at config load so a typo cannot create an unbounded query policy.
 */
internal data class KSLDatabaseExecutionPolicy(
    val queryTimeoutSeconds: Int = 10,
    val maxQueryRows: Int = 10_000,
    val maxBatchRows: Int = 1_000,
    val maxSqlLength: Int = 65_536
) {
    fun validateSql(sql: String) {
        require(sql.isNotBlank()) { "SQL statement must not be blank" }
        require(sql.length <= maxSqlLength) {
            "SQL statement exceeds the configured maximum length ($maxSqlLength characters)"
        }
    }

    fun prepare(
        connection: Connection,
        sql: String,
        parameters: Array<out Any?>,
        query: Boolean
    ): PreparedStatement {
        validateSql(sql)
        val statement = connection.prepareStatement(sql)
        try {
            statement.setQueryTimeout(queryTimeoutSeconds)
            if (query) {
                statement.maxRows = maxQueryRows
            }
            parameters.forEachIndexed { index, value ->
                statement.setObject(index + 1, value)
            }
            return statement
        } catch (error: Throwable) {
            runCatching { statement.close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun validateBatchSize(size: Int) {
        require(size in 1..maxBatchRows) {
            "SQL batch must contain 1..$maxBatchRows rows; received $size"
        }
    }

    companion object {
        fun fromConfig(queryTimeoutSeconds: Int, maxQueryRows: Int, maxBatchRows: Int) =
            KSLDatabaseExecutionPolicy(
                queryTimeoutSeconds = queryTimeoutSeconds.coerceIn(1, 120),
                maxQueryRows = maxQueryRows.coerceIn(1, 100_000),
                maxBatchRows = maxBatchRows.coerceIn(1, 5_000)
            )
    }
}
