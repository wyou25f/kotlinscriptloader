package ru.privateserver.ksl.testing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KSLScriptTestKitTest {
    @Test
    fun suiteRunsCasesSequentiallyAndReportsResults() {
        val report = kslTestSuite("sample") {
            beforeEach { put("value", 42) }
            test("fixture is available and mutable") {
                assertEquals(42, requireValue<Int>("value"))
                put("value", 99)
            }
            test("second case starts with a fresh fixture") {
                assertEquals(42, requireValue<Int>("value"))
            }
        }
        assertEquals(2, report.passed)
        assertEquals(0, report.failed)
        assertTrue(report.successful)
    }

    @Test
    fun suiteHooksRunOnceAroundAllCases() {
        val order = mutableListOf<String>()
        val report = kslTestSuite("lifecycle") {
            beforeAll {
                order += "beforeAll"
                put("suite-ready", true)
            }
            afterAll {
                assertEquals(true, requireValue<Boolean>("suite-ready"))
                order += "afterAll"
            }
            beforeEach { order += "beforeEach" }
            afterEach { order += "afterEach" }
            test("one") {
                assertEquals(true, requireValue<Boolean>("suite-ready"))
                order += "one"
            }
            test("two") {
                assertEquals(true, requireValue<Boolean>("suite-ready"))
                order += "two"
            }
        }
        assertTrue(report.successful)
        assertEquals(listOf("beforeAll", "beforeEach", "one", "afterEach", "beforeEach", "two", "afterEach", "afterAll"), order)
    }

    @Test
    fun suiteTeardownRunsAfterSetupFailureAndIsReported() {
        val order = mutableListOf<String>()
        val report = kslTestSuite("setup-failure") {
            beforeAll { error("setup broke") }
            afterAll { order += "afterAll" }
            test("not executed") { fail("must not run") }
        }
        assertEquals(listOf("afterAll"), order)
        assertFalse(report.successful)
        assertEquals(KSLScriptTestStatus.FAILED, report.cases.single().status)
        assertTrue(report.suiteFailure!!.message!!.contains("setup broke"))
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
    fun assertFailsAndReifiedAssertThrowsReturnExpectedException() {
        val report = kslTestSuite("assertions") {
            test("exception assertions") {
                val first = assertFails(IllegalArgumentException::class.java) {
                    throw IllegalArgumentException("bad input")
                }
                val second = assertThrows<IllegalStateException> {
                    throw IllegalStateException("bad state")
                }
                assertEquals("bad input", first.message)
                assertEquals("bad state", second.message)
            }
        }
        assertEquals(1, report.passed)
    }

    @Test
    fun stringAndIdentityAssertionsAreAvailable() {
        val report = kslTestSuite("assertion-helpers") {
            test("helpers") {
                val token = Any()
                assertSame(token, token)
                assertNotSame(token, Any())
                assertContains("KSL diagnostics", "diagnostics")
                assertStartsWith("KSL", "K")
                assertEndsWith("KSL", "L")
            }
        }
        assertTrue(report.successful)
    }

    @Test
    fun debugAndJsonReportsContainFailureContext() {
        val report = kslTestSuite("reports") {
            test("bad value") { assertEquals(10, 5) }
        }
        val debug = report.debugReport()
        assertTrue(debug.contains("Suite: reports"))
        assertTrue(debug.contains("Expected <10>, actual <5>"))
        assertTrue(debug.contains("KSLScriptTestAssertionError"))
        val json = report.toJson()
        assertTrue(json.contains("\"suite\":\"reports\""))
        assertTrue(json.contains("\"status\":\"FAILED\""))
        assertTrue(json.contains("Expected <10>, actual <5>"))
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
    @Test
    fun parameterizedCasesExpandAndCanBeFilteredByTags() {
        val report = kslTestSuite("parameterized", KSLScriptTestFilter(includeTags = setOf("math"), excludeTags = setOf("slow"))) {
            testEach("double", listOf(1, 2, 3), tags = setOf("math")) { value ->
                assertEquals(value + value, value * 2)
            }
            test("unrelated", tags = setOf("io")) { fail("must be filtered") }
            test("slow math", tags = setOf("math", "slow")) { fail("must be filtered") }
        }
        assertEquals(3, report.passed)
        assertEquals(0, report.failed)
        assertEquals(2, report.filtered)
        assertTrue(report.successful)
        assertTrue(report.debugReport().contains("2 filtered"))
        assertTrue(report.toJson().contains("\\"filtered\\":2"))
    }

    @Test
    fun nameFilterAndAdditionalAssertionsWork() {
        val report = kslTestSuite("assertions", KSLScriptTestFilter(nameContains = "collections")) {
            test("collections") {
                assertEmpty(emptyList<String>())
                assertNotEmpty(listOf(1))
                assertGreaterThan(4, 2)
                assertLessThan(2, 4)
                assertApproxEquals(1.0, 1.0001, 0.001)
            }
            test("not selected") { fail("must not run") }
        }
        assertEquals(1, report.passed)
        assertEquals(1, report.filtered)
    }

    @Test
    fun invalidTagsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLScriptTestSuiteBuilder("bad-tags").test("case", tags = setOf(" ")) {}
        }
    }
}
