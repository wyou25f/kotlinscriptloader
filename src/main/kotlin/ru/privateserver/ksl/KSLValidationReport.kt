package ru.privateserver.ksl

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Immutable result of compiling one script without evaluating it. */
data class KSLScriptValidationResult(
    val scriptId: String,
    val fileName: String,
    val errors: List<String>,
    val warnings: List<String>,
    val compileMillis: Long,
    val failureCode: Int? = null
) {
    val valid: Boolean get() = errors.isEmpty() && failureCode == null
}

/** Writes complete, bounded validation reports outside the live scripts directory. */
internal object KSLValidationReportWriter {
    private const val MAX_REPORT_CHARS = 2_000_000
    private const val MAX_DIAGNOSTIC_CHARS = 4_000
    private const val MAX_REPORTS = 10
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

    fun write(directory: File, results: List<KSLScriptValidationResult>, maxCharacters: Int = MAX_REPORT_CHARS): File {
        Files.createDirectories(directory.toPath())
        val report = buildString {
            appendLine("KSL SCRIPT VALIDATION REPORT")
            appendLine("Generated: ${Instant.now()}")
            appendLine("Scripts checked: ${results.size}")
            appendLine("Passed: ${results.count { it.valid }}")
            appendLine("Failed: ${results.count { !it.valid }}")
            appendLine("Errors: ${results.sumOf { it.errors.size }}")
            appendLine("Warnings: ${results.sumOf { it.warnings.size }}")
            appendLine()
            results.forEach { result ->
                appendLine("=== ${result.scriptId} (${result.fileName}) ===")
                appendLine("Status: ${if (result.valid) "OK" else "FAILED"}")
                appendLine("Compile time: ${result.compileMillis} ms")
                result.failureCode?.let { appendLine("Failure code: $it") }
                if (result.errors.isEmpty()) appendLine("Errors: none")
                else {
                    appendLine("Errors (${result.errors.size}):")
                    result.errors.forEachIndexed { index, error ->
                        appendLine("  ${index + 1}. ${error.take(MAX_DIAGNOSTIC_CHARS)}")
                    }
                }
                if (result.warnings.isNotEmpty()) {
                    appendLine("Warnings (${result.warnings.size}):")
                    result.warnings.forEachIndexed { index, warning ->
                        appendLine("  ${index + 1}. ${warning.take(MAX_DIAGNOSTIC_CHARS)}")
                    }
                }
                appendLine()
            }
        }
        val limit = maxCharacters.coerceIn(256, MAX_REPORT_CHARS)
        val output = if (report.length <= limit) report else
            report.take(limit) + "

[REPORT TRUNCATED: output exceeded $limit characters]
"
        val file = File(directory, "validation-${timestamp.format(Instant.now())}-${System.nanoTime()}.txt")
        Files.writeString(
            file.toPath(),
            output,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        directory.listFiles { candidate -> candidate.isFile && candidate.name.startsWith("validation-") && candidate.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)
            ?.forEach { runCatching { it.delete() } }
        return file
    }
}
