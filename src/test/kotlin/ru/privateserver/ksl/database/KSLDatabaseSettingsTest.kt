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
            KSLDatabaseSettings.parse(type = "mysql", host = "db.example.test", port = -1, name = "ksl", user = "ksl")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLDatabaseSettings.parse(type = "mysql", host = "db.example.test", name = "bad name", user = "ksl")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLDatabaseSettings.parse(type = "mysql", host = "db.example.test", name = "ksl", user = " ")
        }
    }


    @Test
    fun postgresUsesItsNativePortAndVerifiedJdbcUrl() {
        val settings = KSLDatabaseSettings.parse(
            type = "postgresql",
            host = "db.example.test",
            name = "ksl_prod",
            user = "ksl",
            password = "test-secret"
        )
        assertEquals(5432, settings.port)
        assertEquals("jdbc:postgresql://db.example.test:5432/ksl_prod?sslmode=verify-full", settings.jdbcUrl())
        assertTrue(settings.describe().startsWith("PostgreSQL ksl@db.example.test:5432/ksl_prod"))
        assertFalse(settings.describe().contains(settings.password))
    }

    @Test
    fun postgresTlsModesWithoutServerIdentityVerificationNeedOptIn() {
        for (mode in listOf("disable", "allow", "prefer", "require")) {
            assertThrows(IllegalArgumentException::class.java) {
                KSLDatabaseSettings.parse(type = "postgres", user = "ksl", sslMode = mode)
            }
            val allowed = KSLDatabaseSettings.parse(
                type = "postgres",
                user = "ksl",
                sslMode = mode,
                allowInsecureTransport = true
            )
            assertTrue(allowed.jdbcUrl().contains("sslmode=$mode"))
        }
    }

    @Test
    fun sqliteDoesNotRequireRemoteConnectionFields() {
        val settings = KSLDatabaseSettings.parse(type = "sqlite", sslMode = "disable")
        assertEquals(KSLDialect.SQLITE, settings.dialect)
        assertEquals("SQLite", settings.describe())
    }
}
