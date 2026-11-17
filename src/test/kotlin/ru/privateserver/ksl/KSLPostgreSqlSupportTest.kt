package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLPostgreSqlSupportTest {
    @Test
    fun postgresDialectUsesDoubleQuotesAndNativeUpsert() {
        assertEquals("\"key\"", KSLDialect.POSTGRESQL.quote("key"))
        val sql = KSLDialect.POSTGRESQL.upsert(
            "ksl_persist",
            listOf("script_name", "key", "value"),
            listOf("script_name", "key")
        )
        assertTrue(sql.contains("ON CONFLICT(\"script_name\", \"key\")"))
        assertTrue(sql.contains("\"value\" = EXCLUDED.\"value\""))
        assertEquals(3, sql.count { it == '?' })
        assertThrows(IllegalArgumentException::class.java) {
            KSLDialect.POSTGRESQL.quote("key; DROP TABLE")
        }
    }

    @Test
    fun postgresSchemaUsesNoMysqlSpecificDdl() {
        val ddl = Migrations.statements(KSLDialect.POSTGRESQL)
        assertEquals(3, ddl.size)
        assertTrue(ddl.all { it.contains("VARCHAR(190)") && it.contains("TEXT") })
        assertTrue(ddl.all { !it.contains("utf8mb4") && !it.contains("ENGINE=") })
        assertTrue(ddl[1].contains("\"key\""))
    }

    @Test
    fun postgresRemoteKeysHaveTheSameBoundAsOtherRemoteBackends() {
        KSLDialect.POSTGRESQL.requireKeyLength("key", "x".repeat(190))
        assertThrows(IllegalArgumentException::class.java) {
            KSLDialect.POSTGRESQL.requireKeyLength("key", "x".repeat(191))
        }
    }
}
