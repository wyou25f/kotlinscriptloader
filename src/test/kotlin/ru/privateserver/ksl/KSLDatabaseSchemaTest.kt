package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Properties

class KSLDatabaseSchemaTest {
    @Test
    fun schemaMigrationsAreIdempotent() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            assertEquals(3, Migrations.run(it, KSLDialect.SQLITE))
            assertEquals(0, Migrations.run(it, KSLDialect.SQLITE))
        }
    }

    @Test
    fun newerSchemaVersionIsRejectedWithoutDowngrade() {
        val connection = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", Properties())!!
        connection.use {
            it.createStatement().use { statement -> statement.executeUpdate("PRAGMA user_version = 999") }
            assertThrows(IllegalStateException::class.java) { Migrations.run(it, KSLDialect.SQLITE) }
        }
    }
}