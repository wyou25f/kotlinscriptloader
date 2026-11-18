package ru.privateserver.ksl.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class KSLDatabaseTransactionTest {
    @Test
    fun `commits all operations after successful completion`() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            it.createStatement().use { statement ->
                statement.execute("CREATE TABLE migration_test (id INTEGER PRIMARY KEY)")
            }

            val result = KSLDatabaseTransactions.inTransaction(it) {
                it.prepareStatement("INSERT INTO migration_test(id) VALUES (?)").use { statement ->
                    statement.setInt(1, 1)
                    statement.executeUpdate()
                }
                "committed"
            }

            assertEquals("committed", result)
            assertTrue(it.autoCommit)
            it.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM migration_test").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `rolls back every operation when a later operation fails`() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            it.createStatement().use { statement ->
                statement.execute("CREATE TABLE migration_test (id INTEGER PRIMARY KEY)")
            }

            val failure = assertThrows(IllegalStateException::class.java) {
                KSLDatabaseTransactions.inTransaction(it) {
                    it.prepareStatement("INSERT INTO migration_test(id) VALUES (?)").use { statement ->
                        statement.setInt(1, 1)
                        statement.executeUpdate()
                        statement.setInt(1, 2)
                        statement.executeUpdate()
                    }
                    error("second table failed")
                }
            }

            assertEquals("second table failed", failure.message)
            assertTrue(it.autoCommit)
            it.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM migration_test").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `rejects a connection that already belongs to a transaction`() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            it.autoCommit = false
            assertThrows(IllegalStateException::class.java) {
                KSLDatabaseTransactions.inTransaction(it) { Unit }
            }
            assertFalse(it.autoCommit)
            it.rollback()
        }
    }

    @Test
    fun `does not switch auto commit on after rollback fails`() {
        val autoCommit = java.util.concurrent.atomic.AtomicBoolean(true)
        val autoCommitRestores = java.util.concurrent.atomic.AtomicInteger()
        val rollbackCalls = java.util.concurrent.atomic.AtomicInteger()
        val abortCalls = java.util.concurrent.atomic.AtomicInteger()
        val connection = java.lang.reflect.Proxy.newProxyInstance(
            java.sql.Connection::class.java.classLoader,
            arrayOf(java.sql.Connection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit.get()
                "setAutoCommit" -> {
                    val next = args!![0] as Boolean
                    if (next) autoCommitRestores.incrementAndGet()
                    autoCommit.set(next)
                    null
                }
                "rollback" -> {
                    rollbackCalls.incrementAndGet()
                    throw java.sql.SQLException("rollback failed")
                }
                "abort" -> {
                    abortCalls.incrementAndGet()
                    throw java.sql.SQLException("abort unsupported")
                }
                else -> null
            }
        } as java.sql.Connection

        val failure = assertThrows(IllegalStateException::class.java) {
            KSLDatabaseTransactions.inTransaction(connection) {
                error("work failed")
            }
        }

        assertEquals("work failed", failure.message)
        assertEquals(1, rollbackCalls.get())
        assertEquals(1, abortCalls.get())
        assertEquals(0, autoCommitRestores.get())
        assertFalse(autoCommit.get())
        assertEquals(2, failure.suppressed.size)
        assertEquals("rollback failed", failure.suppressed[0].message)
        assertEquals("abort unsupported", failure.suppressed[1].message)
    }

}
