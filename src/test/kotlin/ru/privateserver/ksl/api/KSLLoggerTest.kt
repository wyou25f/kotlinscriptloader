package ru.privateserver.ksl.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLLoggerTest {
    @Test
    fun `administrator minimum level is enforced over logger preferences`() {
        val records = mutableListOf<KSLLogRecord>()
        val logger = KSLLogger.create("script:shop", "database", KSLLoggerConfig(),
            { KSLLogLevel.INFO }, { 4_096 }, records::add)

        logger.debug("query started")
        logger.info("query complete")

        assertEquals(1, records.size)
        assertEquals(KSLLogLevel.INFO, records.single().level)
        assertEquals("query complete", records.single().message)
    }

    @Test
    fun `administrator override can enable debug without changing logger code`() {
        val records = mutableListOf<KSLLogRecord>()
        val logger = KSLLogger.create("addon:example", "storage", KSLLoggerConfig(),
            { KSLLogLevel.DEBUG }, { 4_096 }, records::add)

        assertTrue(logger.isEnabled(KSLLogLevel.DEBUG))
        logger.debug("cache miss")
        assertEquals(KSLLogLevel.DEBUG, records.single().level)
    }

    @Test
    fun `structured fields and newlines are safely bounded`() {
        val records = mutableListOf<KSLLogRecord>()
        val logger = KSLLogger.create("addon:example", "auth", KSLLoggerConfig(),
            { KSLLogLevel.INFO }, { 4_096 }, records::add)

        logger.info("Service ready\nnext line", "provider" to "local", "attempt" to 2)

        val record = records.single()
        assertEquals("Service ready\\nnext line", record.message)
        assertEquals(listOf("provider" to "local", "attempt" to "2"), record.fields)
        assertTrue(record.render(128).length <= 128)
    }

    @Test
    fun `child logger inherits owner and logger policy`() {
        val records = mutableListOf<KSLLogRecord>()
        val logger = KSLLogger.create("script:shop", "root", KSLLoggerConfig(),
            { KSLLogLevel.INFO }, { 4_096 }, records::add)

        logger.child("sql").warn("slow query")

        assertEquals("root.sql", records.single().loggerName)
        assertEquals("script:shop", records.single().ownerKey)
        assertFalse(logger.isEnabled(KSLLogLevel.DEBUG))
    }
}
