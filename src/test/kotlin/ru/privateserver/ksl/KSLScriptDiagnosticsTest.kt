package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class KSLScriptDiagnosticsTest {
    @Test
    fun `records calls timings and failures by operation`() {
        val diagnostics = KSLScriptDiagnostics()
        diagnostics.measure("shop", "event:Join") { "ok" }
        runCatching { diagnostics.measure("shop", "event:Join") { error("boom") } }
        diagnostics.measure("shop", "command:/shop") { Unit }

        val join = diagnostics.snapshots("shop").first { it.operation == "event:Join" }
        assertEquals(2L, join.calls)
        assertEquals(1L, join.failures)
        assertTrue(join.totalNanos >= join.maxNanos)
        assertTrue(join.lastFailure?.contains("boom") == true)
        assertEquals(1L, diagnostics.snapshots("shop").first { it.operation == "command:/shop" }.calls)
    }

    @Test
    fun `inspects resolved and missing script dependencies`() {
        val directory = Files.createTempDirectory("ksl-deps").toFile()
        try {
            val base = directory.resolve("base.kts").apply { writeText("log(\"base\")") }
            val child = directory.resolve("child.kts").apply {
                writeText("// @requires: base, absent\nlog(\"child\")")
            }
            val result = KSLScriptOrder.inspect(listOf(base, child)) { it.nameWithoutExtension }
                .first { it.scriptId == "child" }

            assertEquals(listOf("base", "absent"), result.declared)
            assertEquals(listOf("base"), result.resolved)
            assertEquals(listOf("absent"), result.missing)
        } finally {
            directory.deleteRecursively()
        }
    }
}
