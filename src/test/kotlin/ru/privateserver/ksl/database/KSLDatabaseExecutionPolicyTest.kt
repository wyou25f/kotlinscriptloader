package ru.privateserver.ksl.database

import ru.privateserver.ksl.KSLDatabaseSession

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

    @Test
    fun queryMappersCannotReturnResourcesThatCloseWithTheResultSet() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            val session = KSLDatabaseSession(it, KSLDatabaseExecutionPolicy())

            lateinit var escapedOne: java.sql.ResultSet
            val oneFailure = assertThrows(IllegalStateException::class.java) {
                session.queryOne("SELECT 1") {
                    escapedOne = this
                    this
                }
            }
            assertTrue(oneFailure.message!!.contains("detached"))
            assertTrue(escapedOne.isClosed)

            lateinit var escapedList: java.sql.ResultSet
            val listFailure = assertThrows(IllegalStateException::class.java) {
                session.queryList("SELECT 1") {
                    escapedList = this
                    this
                }
            }
            assertTrue(listFailure.message!!.contains("detached"))
            assertTrue(escapedList.isClosed)
        }
    }


    @Test
    fun queryOneClosesThePreparedStatementWhenTheDriverRejectsMaxRows() {
        val statementClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val statement = java.lang.reflect.Proxy.newProxyInstance(
            java.sql.PreparedStatement::class.java.classLoader,
            arrayOf(java.sql.PreparedStatement::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "setQueryTimeout" -> null
                "setMaxRows" -> throw java.sql.SQLException("maxRows unsupported")
                "close" -> {
                    statementClosed.set(true)
                    null
                }
                else -> null
            }
        } as java.sql.PreparedStatement
        val connection = java.lang.reflect.Proxy.newProxyInstance(
            java.sql.Connection::class.java.classLoader,
            arrayOf(java.sql.Connection::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "prepareStatement" -> statement
                else -> null
            }
        } as java.sql.Connection

        val failure = assertThrows(java.sql.SQLException::class.java) {
            KSLDatabaseSession(connection, KSLDatabaseExecutionPolicy()).queryOne("SELECT 1") { 1 }
        }

        assertEquals("maxRows unsupported", failure.message)
        assertTrue(statementClosed.get())
    }

}
