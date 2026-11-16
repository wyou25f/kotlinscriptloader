package ru.privateserver.ksl.testing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KSLScriptTestKitTest {
    @Test
    fun suiteRunsCasesSequentiallyAndReportsResults() {
        val report = kslTestSuite("sample") {
            beforeEach { put("value", 42) }
            test("fixture is available") {
                assertEquals(42, requireValue<Int>("value"))
            }
            test("second case starts with a fresh fixture") {
                assertNull(value<Int>("value"))
            }
        }
        assertEquals(2, report.passed)
        assertEquals(0, report.failed)
        assertTrue(report.successful)
    }

    @Test
    fun teardownRunsInReverseOrderAfterFailure() {
        val order = mutableListOf<Int>()
        val report = kslTestSuite("cleanup") {
            afterEach { order += 1 }
            afterEach { order += 2 }
            test("failure") { fail("expected") }
        }
        assertEquals(KSLScriptTestStatus.FAILED, report.cases.single().status)
        assertEquals(listOf(2, 1), order)
        assertEquals("expected", report.cases.single().message)
    }

    @Test
    fun cleanupFailureChangesSkippedCaseToFailed() {
        val report = kslTestSuite("skip-cleanup") {
            afterEach { error("cleanup failed") }
            test("skip") { skip("not available") }
        }
        assertEquals(KSLScriptTestStatus.FAILED, report.cases.single().status)
        assertTrue(report.cases.single().message!!.contains("cleanup failed"))
    }

    @Test
    fun assertFailsReturnsExpectedException() {
        val report = kslTestSuite("assertions") {
            test("exception assertion") {
                val error = assertFails(IllegalArgumentException::class.java) {
                    throw IllegalArgumentException("bad input")
                }
                assertEquals("bad input", error.message)
            }
        }
        assertEquals(1, report.passed)
    }

    @Test
    fun duplicateAndBlankNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLScriptTestSuiteBuilder("suite").apply {
                test("same") {}
                test("same") {}
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLScriptTestSuiteBuilder("suite").test("  ") {}
        }
    }

    @Test
    fun suiteTestCountIsBounded() {
        val builder = KSLScriptTestSuiteBuilder("bounded")
        repeat(KSLScriptTestSuiteBuilder.MAX_TESTS_PER_SUITE) { builder.test("case-$it") {} }
        assertThrows(IllegalStateException::class.java) { builder.test("overflow") {} }
    }
}
