package ru.privateserver.ksl.script

import ru.privateserver.ksl.CompiledUnit
import ru.privateserver.ksl.KSLError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class KSLScriptEngineTest {
    private class FakeBackend : KSLScriptEngineBackend {
        var compileResult: CompiledUnit? = null
        var compileFailure: Throwable? = null
        var evaluateResult = true
        var evaluateFailure: Throwable? = null
        var reportedFailures = 0
        var invalidatedScript: String? = null

        override fun invalidateCompileCache(scriptId: String): Int {
            invalidatedScript = scriptId
            return 1
        }

        override fun compile(file: File): CompiledUnit {
            compileFailure?.let { throw it }
            return compileResult ?: CompiledUnit(
                file, "fixture", null, listOf("compile failed"), emptyList(),
                KSLError.SCRIPT_COMPILE, null, 1L
            )
        }

        override fun evaluate(unit: CompiledUnit): Boolean {
            evaluateFailure?.let { throw it }
            return evaluateResult
        }

        override fun reportFailure(unit: CompiledUnit) { reportedFailures++ }
        override fun warmUp() = Unit
        override fun selfTestCompiler(): Pair<Boolean, String> = true to "test"
        override fun sandboxProbe(): Pair<Boolean, String> = true to "test"
    }

    @Test
    fun compileRecordsFailuresAndElapsedTime() {
        val engine = KSLScriptEngine(FakeBackend())
        val unit = engine.compile(File("fixture.kts"))
        val snapshot = engine.snapshot()
        assertFalse(unit.ok)
        assertEquals(1L, snapshot.compilations)
        assertEquals(1L, snapshot.compilationFailures)
        assertEquals(0L, snapshot.compileSuccesses)
        assertEquals(0.0, snapshot.compileSuccessRate)
        assertEquals("fixture", snapshot.lastScriptId)
        assertTrue(snapshot.compileNanos >= 0L)
        assertTrue(snapshot.lastFailure!!.contains("compile failed"))
    }

    @Test
    fun evaluateRecordsSuccessAndFailureOutcomes() {
        val backend = FakeBackend()
        val engine = KSLScriptEngine(backend)
        val unit = CompiledUnit(File("fixture.kts"), "fixture", null, emptyList(), emptyList(), null, null, 0L)
        assertTrue(engine.evaluate(unit))
        backend.evaluateResult = false
        assertFalse(engine.evaluate(unit))
        val snapshot = engine.snapshot()
        assertEquals(2L, snapshot.evaluations)
        assertEquals(1L, snapshot.evaluationFailures)
        assertEquals(1L, snapshot.evaluationSuccesses)
        assertEquals(0.5, snapshot.evaluationSuccessRate)
        assertTrue(snapshot.averageEvaluationNanos >= 0L)
        assertEquals("fixture", snapshot.lastScriptId)
        assertTrue(snapshot.evaluationNanos >= 0L)
    }

    @Test
    fun backendExceptionsAreRethrownAndCounted() {
        val backend = FakeBackend().apply { compileFailure = IllegalStateException("compiler offline") }
        val engine = KSLScriptEngine(backend)
        assertThrows(IllegalStateException::class.java) { engine.compile(File("broken.kts")) }
        val snapshot = engine.snapshot()
        assertEquals(1L, snapshot.compilations)
        assertEquals(1L, snapshot.compilationFailures)
        assertTrue(snapshot.lastFailure!!.contains("compiler offline"))
    }

    @Test
    fun failureReportingIsForwardedToBackend() {
        val backend = FakeBackend()
        val engine = KSLScriptEngine(backend)
        engine.reportFailure(backend.compile(File("fixture.kts")))
        assertEquals(1, backend.reportedFailures)
    }

    @Test
    fun perScriptCompileCacheInvalidationIsForwardedToBackend() {
        val backend = FakeBackend()
        val engine = KSLScriptEngine(backend)

        assertEquals(1, engine.invalidateCompileCache("economy/shop"))
        assertEquals("economy/shop", backend.invalidatedScript)
    }

    @Test
    fun resetClearsEngineMetrics() {
        val engine = KSLScriptEngine(FakeBackend())
        engine.compile(File("fixture.kts"))
        engine.resetMetrics()
        val snapshot = engine.snapshot()
        assertEquals(0L, snapshot.compilations)
        assertEquals(0L, snapshot.compilationFailures)
        assertEquals(0L, snapshot.compileSuccesses)
        assertEquals(0L, snapshot.evaluationSuccesses)
        assertNull(snapshot.lastScriptId)
        assertNull(snapshot.lastFailure)
    }}
