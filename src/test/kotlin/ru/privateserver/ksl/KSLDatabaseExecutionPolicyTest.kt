package ru.privateserver.ksl

import ru.privateserver.ksl.database.KSLDatabaseExecutionPolicy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class KSLDatabaseExecutionPolicyTest {
    @Test
    fun configurationValuesAreBounded() {
        val policy = KSLDatabaseExecutionPolicy.fromConfig(
            queryTimeoutSeconds = 900,
            maxQueryRows = 2_000_000,
            maxBatchRows = 90_000
        )
        assertEquals(120, policy.queryTimeoutSeconds)
        assertEquals(100_000, policy.maxQueryRows)
        assertEquals(5_000, policy.maxBatchRows)
    }

    @Test
    fun preparedStatementsReceiveTimeoutRowCapAndBoundParameters() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            val policy = KSLDatabaseExecutionPolicy(
                queryTimeoutSeconds = 7,
                maxQueryRows = 25,
                maxBatchRows = 8
            )
            policy.prepare(it, "SELECT ?", arrayOf<Any?>(42), query = true).use { statement ->
                assertEquals(7, statement.queryTimeout)
                assertEquals(25, statement.maxRows)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(42, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun blankOrOversizedSqlIsRejectedBeforePreparing() {
        val policy = KSLDatabaseExecutionPolicy(maxSqlLength = 8)
        assertThrows(IllegalArgumentException::class.java) { policy.validateSql("  ") }
        assertThrows(IllegalArgumentException::class.java) { policy.validateSql("SELECT 12345") }
    }

    @Test
    fun batchRowCountIsBoundedAndNonempty() {
        val policy = KSLDatabaseExecutionPolicy(maxBatchRows = 2)
        assertThrows(IllegalArgumentException::class.java) { policy.validateBatchSize(0) }
        assertThrows(IllegalArgumentException::class.java) { policy.validateBatchSize(3) }
        policy.validateBatchSize(2)
    }

    @Test
    fun transactionSessionMapsDetachedResultsAndExecutesBoundedBatches() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            it.autoCommit = false
            val session = KSLDatabaseSession(
                it,
                KSLDatabaseExecutionPolicy(maxQueryRows = 2, maxBatchRows = 4)
            )
            session.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
            session.execute("INSERT INTO notes(id, body) VALUES (?, ?)", 1, "first")
            val batchCounts = session.batch(
                "INSERT INTO notes(id, body) VALUES (?, ?)",
                listOf(listOf<Any?>(2, "second"), listOf<Any?>(3, "third"))
            )
            assertEquals(2, batchCounts.size)
            val first = session.queryOne(
                "SELECT body FROM notes WHERE id = ?",
                1
            ) { getString("body") }
            assertEquals("first", first)
            val rows = session.queryList("SELECT body FROM notes ORDER BY id") { getString("body") }
            assertEquals(listOf("first", "second"), rows)
            it.rollback()
        }
    }

    @Test
    fun sessionRejectsOperationsAfterItsOwnerIsClosed() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            val session = KSLDatabaseSession(
                it,
                KSLDatabaseExecutionPolicy(),
                isActive = { false }
            )
            assertThrows(IllegalStateException::class.java) {
                session.execute("SELECT 1")
            }
        }
    }
}
