package ru.privateserver.ksl.database

import java.sql.Connection

/**
 * Runs one all-or-nothing transaction on a freshly borrowed connection.
 *
 * The caller must pass a connection in auto-commit mode (the normal HikariCP
 * checkout state). Auto-commit is restored even when the operation or rollback fails.
 */
internal object KSLDatabaseTransactions {
    fun <T> inTransaction(connection: Connection, operation: () -> T): T {
        check(connection.autoCommit) {
            "A KSL transaction requires a freshly borrowed connection in auto-commit mode"
        }

        var failure: Throwable? = null
        try {
            connection.autoCommit = false
            val result = operation()
            connection.commit()
            return result
        } catch (error: Throwable) {
            failure = error
            runCatching { connection.rollback() }.onFailure(error::addSuppressed)
            throw error
        } finally {
            try {
                connection.autoCommit = true
            } catch (restoreError: Throwable) {
                failure?.addSuppressed(restoreError) ?: throw restoreError
            }
        }
    }
}
