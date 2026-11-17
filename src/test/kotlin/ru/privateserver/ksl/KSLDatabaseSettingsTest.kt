package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLDatabaseSettingsTest {
    @Test
    fun remoteDatabaseDefaultsToVerifiedTls() {
        val settings = KSLDatabaseSettings.parse(
            type = "mysql",
            host = "db.example.test",
            name = "ksl",
            user = "ksl"
        )
        assertEquals("verify-full", settings.sslMode)
        assertTrue(settings.jdbcUrl().contains("sslMode=verify-full"))
        assertFalse(settings.jdbcUrl().contains("allowPublicKeyRetrieval"))
    }

    @Test
    fun insecureTransportRequiresExplicitOptIn() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLDatabaseSettings.parse(
                type = "mariadb",
                host = "localhost",
                name = "ksl",
                user = "ksl",
                sslMode = "disable"
            )
        }

        val settings = KSLDatabaseSettings.parse(
            type = "mysql",
            host = "localhost",
            name = "ksl",
            user = "ksl",
            sslMode = "disable",
            allowInsecureTransport = true
        )
        assertTrue(settings.jdbcUrl().contains("allowPublicKeyRetrieval=true"))
    }

    @Test
    fun validatesRemoteConnectionFields() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLDatabaseSettings.parse(type = "mysql", host = "db.example.test;DROP", name = "ksl", user = "ksl")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLDatabaseSettings.parse(type = "mysql", host = "db.example.test", port = 0, name = "ksl", user = "ksl")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLDatabaseSettings.parse(type = "mysql", host = "db.example.test", name = "bad name", user = "ksl")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLDatabaseSettings.parse(type = "mysql", host = "db.example.test", name = "ksl", user = " ")
        }
    }

    @Test
    fun sqliteDoesNotRequireRemoteConnectionFields() {
        val settings = KSLDatabaseSettings.parse(type = "sqlite", sslMode = "disable")
        assertEquals(KSLDialect.SQLITE, settings.dialect)
        assertEquals("SQLite", settings.describe())
    }
}
