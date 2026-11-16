package ru.privateserver.ksl.testing

class KSLScriptTestSuite internal constructor(
    val name: String,
    internal val beforeAllHooks: List<KSLScriptTestContext.() -> Unit>,
    internal val afterAllHooks: List<KSLScriptTestContext.() -> Unit>,
    internal val beforeEachHooks: List<KSLScriptTestContext.() -> Unit>,
    internal val afterEachHooks: List<KSLScriptTestContext.() -> Unit>,
    internal val cases: List<KSLScriptTestCase>
)

internal data class KSLScriptTestCase(
    val name: String,
    val tags: Set<String>,
    val body: KSLScriptTestContext.() -> Unit
)

/** Selects tests by name and tags. All includeTags must be present; any excludeTag rejects a case. */
data class KSLScriptTestFilter(
    val nameContains: String? = null,
    val includeTags: Set<String> = emptySet(),
    val excludeTags: Set<String> = emptySet()
) {
    fun matches(name: String, tags: Set<String>): Boolean =
        (nameContains.isNullOrBlank() || name.contains(nameContains, ignoreCase = true)) &&
            tags.containsAll(includeTags) && tags.intersect(excludeTags).isEmpty()
}

/** Builder for a sequential set of KSL script logic tests. */
class KSLScriptTestSuiteBuilder(val name: String) {
    private val beforeAll = ArrayList<KSLScriptTestContext.() -> Unit>()
    private val afterAll = ArrayList<KSLScriptTestContext.() -> Unit>()
    private val beforeHooks = ArrayList<KSLScriptTestContext.() -> Unit>()
    private val afterHooks = ArrayList<KSLScriptTestContext.() -> Unit>()
    private val testCases = ArrayList<KSLScriptTestCase>()
    private val names = HashSet<String>()

    init {
        require(name.isNotBlank()) { "Test suite name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Test suite name is too long" }
    }

    fun beforeAll(block: KSLScriptTestContext.() -> Unit) { beforeAll += block }
    fun afterAll(block: KSLScriptTestContext.() -> Unit) { afterAll += block }
    fun beforeEach(block: KSLScriptTestContext.() -> Unit) { beforeHooks += block }
    fun afterEach(block: KSLScriptTestContext.() -> Unit) { afterHooks += block }

    fun test(name: String, tags: Set<String> = emptySet(), block: KSLScriptTestContext.() -> Unit) {
        addCase(name, tags, block)
    }

    /** Expands input values into independent cases with stable index-based names. */
    fun <T> testEach(name: String, values: Iterable<T>, tags: Set<String> = emptySet(), block: KSLScriptTestContext.(T) -> Unit) {
        require(name.isNotBlank()) { "Test name must not be blank" }
        values.forEachIndexed { index, value -> addCase("$name[$index]", tags) { block(value) } }
    }

    private fun addCase(name: String, tags: Set<String>, block: KSLScriptTestContext.() -> Unit) {
        require(name.isNotBlank()) { "Test name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Test name is too long" }
        require(tags.none { it.isBlank() || it.length > MAX_NAME_LENGTH }) {
            "Test tags must be non-blank and no longer than $MAX_NAME_LENGTH characters"
        }
        check(testCases.size < MAX_TESTS_PER_SUITE) { "Test suite '$this.name' reached its limit ($MAX_TESTS_PER_SUITE)" }
        require(names.add(name)) { "Duplicate test name '$name' in suite '$this.name'" }
        testCases += KSLScriptTestCase(name, tags.toSet(), block)
    }

    fun build(): KSLScriptTestSuite = KSLScriptTestSuite(
        name, beforeAll.toList(), afterAll.toList(),
        beforeHooks.toList(), afterHooks.toList(), testCases.toList()
    )

    companion object {
        const val MAX_TESTS_PER_SUITE = 512
        private const val MAX_NAME_LENGTH = 160
    }
}

enum class KSLScriptTestStatus { PASSED, FAILED, SKIPPED }

data class KSLScriptTestCaseResult(
    val name: String,
    val status: KSLScriptTestStatus,
    val durationNanos: Long,
    val message: String? = null,
    val failure: Throwable? = null
) {
    val durationMillis: Double get() = durationNanos / 1_000_000.0
    val failureType: String? get() = failure?.javaClass?.simpleName
}

data class KSLScriptTestRunResult(
    val suiteName: String,
    val cases: List<KSLScriptTestCaseResult>,
    val durationNanos: Long,
    val suiteFailure: Throwable? = null,
    val filtered: Int = 0
) {
    val passed: Int get() = cases.count { it.status == KSLScriptTestStatus.PASSED }
    val failed: Int get() = cases.count { it.status == KSLScriptTestStatus.FAILED }
    val skipped: Int get() = cases.count { it.status == KSLScriptTestStatus.SKIPPED }
    val successful: Boolean get() = failed == 0 && suiteFailure == null
    val durationMillis: Double get() = durationNanos / 1_000_000.0

    /** Human-readable report with failure causes and the most useful stack frames. */
    fun debugReport(): String = buildString {
        appendLine("KSL TEST REPORT")
        appendLine("Suite: $suiteName")
        appendLine("Result: ${if (successful) "PASSED" else "FAILED"}")
        appendLine("Tests: $passed passed, $failed failed, $skipped skipped, $filtered filtered")
        appendLine("Duration: ${"%.2f".format(java.util.Locale.ROOT, durationMillis)} ms")
        cases.forEach { result ->
            appendLine()
            appendLine("[${result.status}] ${result.name} (${"%.2f".format(java.util.Locale.ROOT, result.durationMillis)} ms)")
            result.message?.let { appendLine("  Message: $it") }
            result.failure?.let { appendFailure(this, it, "  ") }
        }
        suiteFailure?.let {
            appendLine()
            appendLine("Suite lifecycle failure:")
            appendFailure(this, it, "  ")
        }
    }.trimEnd()

    /** Stable, dependency-free JSON report suitable for CI log collection. */
    fun toJson(): String = buildString {
        append("{")
        append("\"suite\":").append(jsonString(suiteName)).append(',')
        append("\"successful\":").append(successful).append(',')
        append("\"durationNanos\":").append(durationNanos).append(',')
        append("\"summary\":{")
        append("\"passed\":").append(passed).append(',')
        append("\"failed\":").append(failed).append(',')
        append("\"skipped\":").append(skipped).append(',')
        append("\"filtered\":").append(filtered).append("},")
        append("\"suiteFailure\":")
        appendFailureJson(this, suiteFailure)
        append(",\"cases\":[")
        cases.forEachIndexed { index, result ->
            if (index > 0) append(',')
            append("{")
            append("\"name\":").append(jsonString(result.name)).append(',')
            append("\"status\":").append(jsonString(result.status.name)).append(',')
            append("\"durationNanos\":").append(result.durationNanos).append(',')
            append("\"message\":").append(result.message?.let(::jsonString) ?: "null").append(',')
            append("\"failureType\":").append(result.failureType?.let(::jsonString) ?: "null").append(',')
            append("\"stackTrace\":")
            appendStackTraceJson(this, result.failure)
            append("}")
        }
        append("]}")
    }

    override fun toString(): String =
        "KSL tests '$suiteName': $passed passed, $failed failed, $skipped skipped (%.2f ms)"
            .format(java.util.Locale.ROOT, durationMillis)

    private companion object {
        private const val MAX_STACK_FRAMES = 24

        fun appendFailure(out: StringBuilder, error: Throwable, indent: String) {
            out.appendLine("${indent}${error.javaClass.simpleName}: ${error.message ?: "No message"}")
            error.stackTrace.take(MAX_STACK_FRAMES).forEach { frame ->
                out.appendLine("${indent}at $frame")
            }
            error.suppressed.forEach { suppressed ->
                out.appendLine("${indent}Suppressed:")
                appendFailure(out, suppressed, "$indent  ")
            }
            error.cause?.takeIf { it !== error }?.let {
                out.appendLine("${indent}Caused by:")
                appendFailure(out, it, "$indent  ")
            }
        }

        fun appendFailureJson(out: StringBuilder, error: Throwable?) {
            if (error == null) { out.append("null"); return }
            out.append("{\"type\":").append(jsonString(error.javaClass.name))
            out.append(",\"message\":").append(error.message?.let(::jsonString) ?: "null")
            out.append(",\"stackTrace\":")
            appendStackTraceJson(out, error)
            out.append("}")
        }

        fun appendStackTraceJson(out: StringBuilder, error: Throwable?) {
            out.append('[')
            error?.stackTrace?.take(MAX_STACK_FRAMES)?.forEachIndexed { index, frame ->
                if (index > 0) out.append(',')
                out.append(jsonString(frame.toString()))
            }
            out.append(']')
        }

        fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }
}

class KSLScriptTestAssertionError(message: String, cause: Throwable? = null) : AssertionError(message) {
    init { if (cause != null) initCause(cause) }
}

class KSLScriptTestSkip internal constructor(val reason: String) : RuntimeException(reason)

/** Fresh fixture storage and assertions for a single test case. */
class KSLScriptTestContext internal constructor(initialFixtures: Map<String, Any?> = emptyMap()) {
    private val fixtures = HashMap<String, Any?>().apply { putAll(initialFixtures) }

    internal fun fixtureSnapshot(): Map<String, Any?> = HashMap(fixtures)

    fun <T : Any> put(key: String, value: T) {
        require(key.isNotBlank()) { "Fixture key must not be blank" }
        fixtures[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> value(key: String): T? = fixtures[key] as? T

    fun <T : Any> requireValue(key: String): T =
        value<T>(key) ?: throw KSLScriptTestAssertionError("Fixture '$key' is missing")

    fun remove(key: String): Boolean = fixtures.remove(key) != null

    fun skip(reason: String): Nothing {
        require(reason.isNotBlank()) { "Skip reason must not be blank" }
        throw KSLScriptTestSkip(reason)
    }

    fun fail(message: String): Nothing = throw KSLScriptTestAssertionError(message)

    /**
     * Runs every assertion and reports all failures together instead of stopping at the first one.
     * Each assertion should be independent; later assertions still run after an earlier failure.
     */
    fun assertAll(vararg assertions: () -> Unit) {
        require(assertions.isNotEmpty()) { "assertAll requires at least one assertion" }
        val failures = ArrayList<Throwable>()
        assertions.forEach { assertion ->
            try {
                assertion()
            } catch (error: Throwable) {
                if (error is KSLScriptTestSkip) throw error
                failures += error
            }
        }
        if (failures.isNotEmpty()) {
            val combined = KSLScriptTestAssertionError(
                "${failures.size} grouped assertion(s) failed",
                failures.first()
            )
            failures.drop(1).forEach(combined::addSuppressed)
            throw combined
        }
    }

    fun assertContains(actual: Collection<*>, expected: Any?, message: String? = null) {
        if (expected !in actual) fail(message ?: "Expected collection <$actual> to contain <$expected>")
    }

    fun assertTrue(actual: Boolean, message: String? = null) {
        if (!actual) fail(message ?: "Expected condition to be true")
    }

    fun assertFalse(actual: Boolean, message: String? = null) {
        if (actual) fail(message ?: "Expected condition to be false")
    }

    fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
        if (expected != actual) fail(message ?: "Expected <$expected>, actual <$actual>")
    }

    fun assertNotEquals(unexpected: Any?, actual: Any?, message: String? = null) {
        if (unexpected == actual) fail(message ?: "Did not expect <$actual>")
    }

    fun assertSame(expected: Any?, actual: Any?, message: String? = null) {
        if (expected !== actual) fail(message ?: "Expected both values to reference the same object")
    }

    fun assertNotSame(unexpected: Any?, actual: Any?, message: String? = null) {
        if (unexpected === actual) fail(message ?: "Expected values to reference different objects")
    }

    fun assertContains(actual: CharSequence, expectedPart: CharSequence, message: String? = null) {
        if (!actual.contains(expectedPart)) {
            fail(message ?: "Expected <$actual> to contain <$expectedPart>")
        }
    }

    fun assertStartsWith(actual: String, prefix: String, message: String? = null) {
        if (!actual.startsWith(prefix)) fail(message ?: "Expected <$actual> to start with <$prefix>")
    }

    fun assertEndsWith(actual: String, suffix: String, message: String? = null) {
        if (!actual.endsWith(suffix)) fail(message ?: "Expected <$actual> to end with <$suffix>")
    }

    fun assertEmpty(actual: Collection<*>, message: String? = null) {
        if (actual.isNotEmpty()) fail(message ?: "Expected an empty collection, actual size <${actual.size}>")
    }

    fun assertNotEmpty(actual: Collection<*>, message: String? = null) {
        if (actual.isEmpty()) fail(message ?: "Expected a non-empty collection")
    }

    fun assertGreaterThan(actual: Number, threshold: Number, message: String? = null) {
        if (!(actual.toDouble() > threshold.toDouble())) fail(message ?: "Expected <$actual> to be greater than <$threshold>")
    }

    fun assertLessThan(actual: Number, threshold: Number, message: String? = null) {
        if (!(actual.toDouble() < threshold.toDouble())) fail(message ?: "Expected <$actual> to be less than <$threshold>")
    }

    fun assertApproxEquals(expected: Double, actual: Double, tolerance: Double = 0.000001, message: String? = null) {
        require(tolerance.isFinite() && tolerance >= 0.0) { "Tolerance must be finite and non-negative" }
        if (!expected.isFinite() || !actual.isFinite() || kotlin.math.abs(expected - actual) > tolerance) {
            fail(message ?: "Expected <$actual> to be within <$tolerance> of <$expected>")
        }
    }

    fun assertNull(actual: Any?, message: String? = null) {
        if (actual != null) fail(message ?: "Expected null, actual <$actual>")
    }

    fun <T : Any> assertNotNull(actual: T?, message: String? = null): T =
        actual ?: run { fail(message ?: "Expected a non-null value") }

    fun <T : Throwable> assertFails(expectedType: Class<T>, block: () -> Unit): T {
        val thrown = try { block(); null } catch (error: Throwable) { error }
        if (thrown == null) fail("Expected ${expectedType.simpleName}, but nothing was thrown")
        if (!expectedType.isInstance(thrown)) {
            fail("Expected ${expectedType.simpleName}, but caught ${thrown::class.simpleName}")
        }
        return expectedType.cast(thrown)
    }

    inline fun <reified T : Throwable> assertThrows(noinline block: () -> Unit): T =
        assertFails(T::class.java, block)
}

object KSLScriptTestRunner {
    /** Runs cases sequentially; suite teardown and per-case cleanup are attempted even after failures. */
    fun run(suite: KSLScriptTestSuite, filter: KSLScriptTestFilter = KSLScriptTestFilter()): KSLScriptTestRunResult {
        val suiteStarted = System.nanoTime()
        val selectedCases = suite.cases.filter { filter.matches(it.name, it.tags) }
        val filteredCount = suite.cases.size - selectedCases.size
        val suiteContext = KSLScriptTestContext()
        var suiteFailure: Throwable? = null

        try {
            suite.beforeAllHooks.forEach { it(suiteContext) }
        } catch (error: Throwable) {
            suiteFailure = error
        }

        val setupFailure = suiteFailure
        val results = if (setupFailure != null) {
            selectedCases.map { testCase ->
                KSLScriptTestCaseResult(
                    testCase.name, KSLScriptTestStatus.FAILED, 0L,
                    "Suite setup failed: ${setupFailure.message ?: setupFailure::class.simpleName}",
                    setupFailure
                )
            }
        } else {
            selectedCases.map { testCase ->
                val testStarted = System.nanoTime()
                val context = KSLScriptTestContext(suiteContext.fixtureSnapshot())
                var status = KSLScriptTestStatus.PASSED
                var message: String? = null
                var failure: Throwable? = null

                try {
                    suite.beforeEachHooks.forEach { it(context) }
                    testCase.body(context)
                } catch (skip: KSLScriptTestSkip) {
                    status = KSLScriptTestStatus.SKIPPED
                    message = skip.reason
                } catch (error: Throwable) {
                    status = KSLScriptTestStatus.FAILED
                    message = error.message ?: error::class.simpleName
                    failure = error
                } finally {
                    suite.afterEachHooks.asReversed().forEach { cleanup ->
                        try {
                            cleanup(context)
                        } catch (cleanupError: Throwable) {
                            if (failure == null) {
                                status = KSLScriptTestStatus.FAILED
                                message = cleanupError.message ?: cleanupError::class.simpleName
                                failure = cleanupError
                            } else {
                                failure?.addSuppressed(cleanupError)
                            }
                        }
                    }
                }

                KSLScriptTestCaseResult(
                    testCase.name, status,
                    (System.nanoTime() - testStarted).coerceAtLeast(0L),
                    message, failure
                )
            }
        }

        suite.afterAllHooks.asReversed().forEach { cleanup ->
            try {
                cleanup(suiteContext)
            } catch (error: Throwable) {
                if (suiteFailure == null) suiteFailure = error else suiteFailure?.addSuppressed(error)
            }
        }

        return KSLScriptTestRunResult(
            suite.name, results,
            (System.nanoTime() - suiteStarted).coerceAtLeast(0L),
            suiteFailure,
            filteredCount
        )
    }
}

fun kslTestSuite(
    name: String,
    filter: KSLScriptTestFilter = KSLScriptTestFilter(),
    block: KSLScriptTestSuiteBuilder.() -> Unit
): KSLScriptTestRunResult =
    KSLScriptTestRunner.run(KSLScriptTestSuiteBuilder(name).apply(block).build(), filter)
