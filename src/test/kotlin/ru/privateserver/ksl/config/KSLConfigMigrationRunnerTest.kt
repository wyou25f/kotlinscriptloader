package ru.privateserver.ksl.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLConfigMigrationRunnerTest {
    @Test
    fun `applies migrations to a working copy and persists once`() {
        val original = mutableMapOf("name" to "KSL")
        var persisted: MutableMap<String, String>? = null
        var persistCalls = 0
        val migrations: Map<Int, MutableMap<String, String>.() -> Unit> = mapOf(
            1 to { put("first", "applied") },
            2 to { put("second", "applied") }
        )

        val version = KSLConfigMigrationRunner.migrate(
            currentVersion = 0,
            targetVersion = 2,
            migrations = migrations,
            createWorkingCopy = { original.toMutableMap() },
            setVersion = { config, next -> config["version"] = next.toString() },
            persist = { config -> persisted = config.toMutableMap(); persistCalls++ }
        )

        assertEquals(2, version)
        assertEquals("KSL", original["name"])
        assertEquals("applied", persisted?.get("first"))
        assertEquals("applied", persisted?.get("second"))
        assertEquals("2", persisted?.get("version"))
        assertEquals(1, persistCalls)
    }

    @Test
    fun `does not persist a partially migrated copy when a migration fails`() {
        var persisted = false
        val migrations: Map<Int, MutableMap<String, String>.() -> Unit> = mapOf(
            1 to { put("first", "applied") },
            2 to { error("migration failed") }
        )

        assertThrows(IllegalStateException::class.java) {
            KSLConfigMigrationRunner.migrate(
                currentVersion = 0,
                targetVersion = 2,
                migrations = migrations,
                createWorkingCopy = { mutableMapOf() },
                setVersion = { config, next -> config["version"] = next.toString() },
                persist = { persisted = true }
            )
        }
        assertFalse(persisted)
    }

    @Test
    fun `rejects gaps before creating a working copy`() {
        var copyCreated = false
        val migrations: Map<Int, MutableMap<String, String>.() -> Unit> =
            mapOf(1 to { put("first", "applied") })

        assertThrows(IllegalArgumentException::class.java) {
            KSLConfigMigrationRunner.migrate(
                currentVersion = 0,
                targetVersion = 2,
                migrations = migrations,
                createWorkingCopy = { copyCreated = true; mutableMapOf() },
                setVersion = { _, _ -> },
                persist = {}
            )
        }
        assertFalse(copyCreated)
    }
}
