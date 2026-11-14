package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.sql.SQLTimeoutException

class KSLErrorsTest {
    @Test
    fun `classifies common SQL states and vendor failures`() {
        assertEquals(KSLError.DB_QUERY_SYNTAX, KSLErrors.classifyDatabaseFailure(SQLException("syntax", "42000"), KSLError.DB_TABLE_FAILED))
        assertEquals(KSLError.DB_CONSTRAINT_VIOLATION, KSLErrors.classifyDatabaseFailure(SQLException("constraint", "23000"), KSLError.DB_TABLE_FAILED))
        assertEquals(KSLError.DB_DEADLOCK, KSLErrors.classifyDatabaseFailure(SQLException("deadlock", "40001", 1213), KSLError.DB_TABLE_FAILED))
        assertEquals(KSLError.DB_QUERY_TIMEOUT, KSLErrors.classifyDatabaseFailure(SQLTimeoutException("query timed out"), KSLError.DB_TABLE_FAILED))
        assertEquals(KSLError.DB_READ_ONLY, KSLErrors.classifyDatabaseFailure(SQLException("read only", "25006"), KSLError.DB_TABLE_FAILED))
        assertEquals(KSLError.DB_CONNECTION_LOST, KSLErrors.classifyDatabaseFailure(SQLException("connection reset", "08006"), KSLError.DB_TABLE_FAILED))
    }

    @Test
    fun `classifies storage failures and preserves operation fallback`() {
        assertEquals(KSLError.DB_DISK_FULL, KSLErrors.classifyDatabaseFailure(SQLException("No space left on device"), KSLError.DB_PERSIST_SAVE_FAILED))
        assertEquals(KSLError.DB_AUTHENTICATION_FAILED, KSLErrors.classifyDatabaseFailure(SQLException("Access denied", "28000"), KSLError.DB_INIT_FAILED))
        assertEquals(KSLError.DB_PERSIST_SAVE_FAILED, KSLErrors.classifyDatabaseFailure(IllegalStateException("unclassified"), KSLError.DB_PERSIST_SAVE_FAILED))
    }

    @Test
    fun `deepest cause is cycle safe`() {
        val outer = IllegalStateException("outer")
        val inner = IllegalArgumentException("inner")
        outer.initCause(inner)
        assertEquals(inner, KSLErrors.deepestCause(outer))

        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(second, KSLErrors.deepestCause(first))
    }
}