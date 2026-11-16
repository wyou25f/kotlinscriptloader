package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class KSLValidationReportTest {
    @Test
    fun `validation result distinguishes errors from warnings`() {
        val result = KSLScriptValidationResult(
            scriptId = "events/join",
            fileName = "join.kts",
            errors = emptyList(),
            warnings = listOf("deprecated API"),
            compileMillis = 120
        )
        assertTrue(result.valid)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `full report keeps fifty diagnostics readable and records counts`() {
        val directory = Files.createTempDirectory("ksl-validation-report").toFile()
        try {
            val errors = (1..50).map { "line $it: unresolved reference" }
            val result = KSLScriptValidationResult("broken", "broken.kts", errors, emptyList(), 42, 6156)
            val file = KSLValidationReportWriter.write(directory, listOf(result))
            val content = file.readText()
            assertFalse(result.valid)
            assertTrue(content.contains("Errors: 50"))
            assertTrue(content.contains("50. line 50: unresolved reference"))
            assertTrue(content.contains("Failure code: 6156"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `oversized report is truncated with an explicit marker`() {
        val directory = Files.createTempDirectory("ksl-validation-limit").toFile()
        try {
            val result = KSLScriptValidationResult("large", "large.kts", listOf("x".repeat(500)), emptyList(), 1)
            val file = KSLValidationReportWriter.write(directory, listOf(result), maxCharacters = 256)
            assertTrue(file.readText().contains("REPORT TRUNCATED"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
