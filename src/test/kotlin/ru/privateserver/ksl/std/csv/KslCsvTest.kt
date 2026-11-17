package ru.privateserver.ksl.std.csv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        assertEquals(listOf("Bob"), table.select("name").column("name"))
        table.sortBy("name", descending = true)
        assertEquals("Bob", table[0]["name"])
        assertTrue(table.removeColumn("rank"))
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
