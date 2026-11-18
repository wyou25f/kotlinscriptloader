package ru.privateserver.ksl.database

import java.sql.Connection
import java.util.concurrent.Executor

/**
 * Runs one all-or-nothing transaction on a freshly borrowed connection.
 *
 * The caller must pass a connection in auto-commit mode (the normal HikariCP
 * checkout state). Auto-commit is restored after success or a successful rollback.
 * If rollback fails, the connection is aborted where supported and is not switched
 * back to auto-commit: JDBC may commit an unfinished transaction on that transition.
 * The caller must close the borrowed connection in all cases.
 */
internal object KSLDatabaseTransactions {
    fun <T> inTransaction(connection: Connection, operation: () -> T): T {
        check(connection.autoCommit) {
            "A KSL transaction requires a freshly borrowed connection in auto-commit mode"
        }

        var failure: Throwable? = null
        var transactionStarted = false
        var rollbackSucceeded = true
        try {
            // Mark the transition before calling the driver: a setter may throw
            // after partially changing connection state.
            transactionStarted = true
            connection.autoCommit = false
            val result = operation()
            connection.commit()
            return result
        } catch (error: Throwable) {
            failure = error
            if (transactionStarted) {
                try {
                    connection.rollback()
                } catch (rollbackError: Throwable) {
                    rollbackSucceeded = false
                    error.addSuppressed(rollbackError)
                    // Do not set autoCommit=true after a failed rollback. Per JDBC,
                    // that transition may commit any still-open transaction.
                    runCatching {
                        connection.abort(Executor { command -> command.run() })
                    }.onFailure(error::addSuppressed)
                }
            }
            throw error
        } finally {
            if (!transactionStarted || rollbackSucceeded) {
                try {
                    connection.autoCommit = true
                } catch (restoreError: Throwable) {
                    failure?.addSuppressed(restoreError) ?: throw restoreError
                }
            }
        }
    }
}
