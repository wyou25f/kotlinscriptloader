package ru.privateserver.ksl.std.csv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.nio.file.Files

class KslCsvTest {

    @Test
    fun parsesAndWritesQuotedFieldsCommasQuotesAndEmbeddedNewlines() {
        val source = "id,name,note\r\n1,Alice,\"hello, world\"\r\n2,\"Bob \"\"B\"\"\",\"line one\r\nline two\""
        val table = KslCsvCodec.parse(source)

        assertEquals(2, table.rowCount)
        assertEquals("Alice", table[0]["name"])
        assertEquals("hello, world", table[0]["note"])
        assertEquals("Bob \"B\"", table[1]["name"])
        assertEquals("line one\r\nline two", table[1]["note"])

        val encoded = KslCsvCodec.encode(table)
        val decoded = KslCsvCodec.parse(encoded)
        assertEquals(table.toMaps(), decoded.toMaps())
    }

    @Test
    fun supportsCustomDelimiterNoHeaderAndTrimmingUnquotedCells() {
        val options = KslCsvOptions(
            delimiter = ';',
            hasHeader = false,
            trimUnquoted = true
        )
        val table = "  7 ; true ; \" two;parts \"".parseCsv(options)

        assertEquals(listOf("column1", "column2", "column3"), table.columns)
        assertEquals("7", table[0][0])
        assertEquals("true", table[0].getBoolean("column2").toString())
        assertEquals(" two;parts ", table[0][2])
    }

    @Test
    fun tableSupportsTypedRowsColumnsSelectionAndFiltering() {
        val table = csvTable("id", "name", "active") {
            addRow("id" to 1, "name" to "Alice", "active" to true)
            addRow("id" to 2, "name" to "Bob", "active" to false)
            addColumn("rank", 0)
        }

        assertEquals(2, table.rowCount)
        assertEquals(1, table[0].requireInt("id"))
        assertTrue(table[0].requireBoolean("active"))
        assertEquals(listOf("Alice", "Bob"), table.column("name"))
        assertEquals(1, table.filterRows { it.getBoolean("active") }.rowCount)
        val projected = table.select("name")
        assertEquals(listOf("Alice", "Bob"), projected.column("name"))
        assertEquals(listOf("Bob"), table.filterRows { !it.getBoolean("active") }.column("name"))
        table.sortBy("name", descending = true)
        assertEquals("Bob", table[0]["name"])
        assertTrue(table.removeColumn("rank"))
    }

    @Test
    fun tableSupportsUpsertGroupingProjectionAndCopyBasedOperations() {
        val table = csvTable("id", "name", "active") {
            addRow(1, "Alice", true)
            addRow(2, "Bob", false)
            addRow(3, "Alice", true)
        }
        assertTrue(table.containsColumn("name"))
        assertEquals(1, table.indexOfColumn("name"))
        assertTrue(table.renameColumn("name", "display_name"))
        assertEquals(listOf("Alice", "Bob", "Alice"), table.column("display_name"))
        val updated = table.upsertBy("id", mapOf("id" to 2, "display_name" to "Bobby", "active" to true))
        assertEquals("Bobby", updated["display_name"])
        assertEquals(2, table.findAll("display_name", "Alice").size)
        assertEquals(2, table.distinctBy("display_name").rowCount)
        assertEquals(setOf("Alice", "Bobby"), table.groupRowsBy("display_name").keys)
        assertEquals(2, table.takeRows(2).rowCount)
        assertEquals(1, table.dropRows(2).rowCount)
        assertEquals("Bobby", table.sortedBy("display_name").last()["display_name"])
        assertEquals("Alice", table.first()["display_name"])
        assertEquals("Alice", table.last()["display_name"])
        val before = table.toMaps()
        assertEquals(3, table.updateRows({ it.getBoolean("active") }) {
            this["display_name"] = get("display_name").uppercase()
        })
        assertTrue(before != table.toMaps())
        assertTrue(table.dropRows(100).isEmpty)
    }

    @Test
    fun typedRowsSupportDecimalUuidDatesAndInstants() {
        val id = UUID.randomUUID()
        val date = LocalDate.parse("2026-11-18")
        val instant = Instant.parse("2026-11-18T03:00:00Z")
        val table = csvTable("price", "uuid", "date", "instant") {
            addRow(BigDecimal("12.3400"), id, date, instant)
        }
        val row = table.first()
        assertEquals(BigDecimal("12.34"), row.requireDecimal("price"))
        assertEquals(id, row.requireUuid("uuid"))
        assertEquals(date, row.requireLocalDate("date"))
        assertEquals(instant, row.requireInstant("instant"))
        assertTrue(runCatching { row.requireUuid("price") }.isFailure)
    }

    @Test
    fun csvSchemaValidatesTypesAndNormalizesDefaultsWithoutMutatingSource() {
        val source = csvTable("id", "name") {
            addRow("7", " Alice ")
            addRow("not-an-int", "")
        }
        val schema = csvSchema {
            int("id", required = true)
            text("name", required = true, trim = true)
            bool("enabled", defaultValue = true)
            text("country", defaultValue = "Unknown")
        }
        val normalized = schema.normalize(source)
        assertEquals(listOf("id", "name", "enabled", "country"), normalized.table.columns)
        assertEquals("Alice", normalized.table[0]["name"])
        assertEquals("true", normalized.table[1]["enabled"])
        assertEquals("Unknown", normalized.table[1]["country"])
        assertEquals(2, normalized.validation.issues.size)
        assertFalse(normalized.isValid)
        assertEquals(listOf("id", "name"), source.columns)
        assertEquals(" Alice ", source[0]["name"])
    }

    @Test
    fun csvSchemaReportsMissingAndUnexpectedColumnsAndBoundsIssueCount() {
        val table = csvTable("extra") { addRow("a"); addRow("b"); addRow("c") }
        val schema = csvSchema(maxIssues = 2) { int("id", required = true) }
        val result = schema.validate(table)
        assertFalse(result.isValid)
        assertTrue(result.issuesTruncated)
        assertEquals(2, result.errorCount)
        assertTrue(result.issues.any { it.code == "missing_column" })
        assertTrue(result.issues.any { it.code == "unexpected_column" })
    }

    @Test
    fun rejectsMalformedQuotesDuplicateHeadersAndResourceLimitViolations() {
        assertThrows(IllegalArgumentException::class.java) { KslCsvCodec.parse("a,b\n1,\"bad") }
        assertThrows(IllegalArgumentException::class.java) { KslCsvCodec.parse("a,a\n1,2") }
        assertThrows(IllegalArgumentException::class.java) {
            KslCsvCodec.parse("a,b\n1,2", KslCsvOptions(maxRows = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KslCsvCodec.parse("a\n12345", KslCsvOptions(maxFieldCharacters = 4))
        }
    }

    @Test
    fun fileApiUsesUtf8AndAtomicWriteHelper() {
        val directory = Files.createTempDirectory("ksl-csv-test").toFile()
        val file = directory.resolve("users.csv")
        try {
            val table = csvTable("id", "name") { addRow(12, "Рома") }
            KslCsvFiles.write(file, table)
            val loaded = KslCsvFiles.read(file)!!
            assertEquals("Рома", loaded[0]["name"])
            assertTrue(KslCsvFiles.read(directory.resolve("missing.csv")) == null)
        } finally {
            directory.deleteRecursively()
        }
    }
}
