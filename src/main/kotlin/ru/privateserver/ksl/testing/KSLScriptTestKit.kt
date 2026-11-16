package ru.privateserver.ksl.testing

class KSLScriptTestSuite internal constructor(
    val name: String,
    internal val beforeEachHooks: List<KSLScriptTestContext.() -> Unit>,
    internal val afterEachHooks: List<KSLScriptTestContext.() -> Unit>,
    internal val cases: List<KSLScriptTestCase>
)

internal data class KSLScriptTestCase(
    val name: String,
    val body: KSLScriptTestContext.() -> Unit
)

/** Builder for a sequential set of KSL script logic tests. */
class KSLScriptTestSuiteBuilder(val name: String) {
    private val beforeHooks = ArrayList<KSLScriptTestContext.() -> Unit>()
    private val afterHooks = ArrayList<KSLScriptTestContext.() -> Unit>()
    private val testCases = ArrayList<KSLScriptTestCase>()
    private val names = HashSet<String>()

    init {
        require(name.isNotBlank()) { "Test suite name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Test suite name is too long" }
    }

    fun beforeEach(block: KSLScriptTestContext.() -> Unit) {
        beforeHooks += block
    }

    fun afterEach(block: KSLScriptTestContext.() -> Unit) {
        afterHooks += block
    }

    fun test(name: String, block: KSLScriptTestContext.() -> Unit) {
        require(name.isNotBlank()) { "Test name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Test name is too long" }
        check(testCases.size < MAX_TESTS_PER_SUITE) {
            "Test suite '$name' reached its limit ($MAX_TESTS_PER_SUITE)"
        }
        require(names.add(name)) { "Duplicate test name '$name' in suite '$this.name'" }
        testCases += KSLScriptTestCase(name, block)
    }

    fun build(): KSLScriptTestSuite = KSLScriptTestSuite(
        name,
        beforeHooks.toList(),
        afterHooks.toList(),
        testCases.toList()
    )

    companion object {
        const val MAX_TESTS_PER_SUITE = 512
        private const val MAX_NAME_LENGTH = 160
    }
}

enum class KSLScriptTestStatus {
    PASSED,
    FAILED,
    SKIPPED
}

data class KSLScriptTestCaseResult(
    val name: String,
    val status: KSLScriptTestStatus,
    val durationNanos: Long,
    val message: String? = null,
    val failure: Throwable? = null
) {
    val durationMillis: Double get() = durationNanos / 1_000_000.0
}

data class KSLScriptTestRunResult(
    val suiteName: String,
    val cases: List<KSLScriptTestCaseResult>,
    val durationNanos: Long
) {
    val passed: Int get() = cases.count { it.status == KSLScriptTestStatus.PASSED }
    val failed: Int get() = cases.count { it.status == KSLScriptTestStatus.FAILED }
    val skipped: Int get() = cases.count { it.status == KSLScriptTestStatus.SKIPPED }
    val successful: Boolean get() = failed == 0
    val durationMillis: Double get() = durationNanos / 1_000_000.0

    override fun toString(): String =
        "KSL tests '$suiteName': $passed passed, $failed failed, $skipped skipped (%.2f ms)"
            .format(java.util.Locale.ROOT, durationMillis)
}

class KSLScriptTestAssertionError(message: String, cause: Throwable? = null) : AssertionError(message) {
    init {
        if (cause != null) initCause(cause)
    }
}

class KSLScriptTestSkip internal constructor(val reason: String) : RuntimeException(reason)

/** Fresh fixture storage and assertions for a single test case. */
class KSLScriptTestContext internal constructor() {
    private val fixtures = HashMap<String, Any?>()

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

    fun assertNull(actual: Any?, message: String? = null) {
        if (actual != null) fail(message ?: "Expected null, actual <$actual>")
    }

    fun <T : Any> assertNotNull(actual: T?, message: String? = null): T =
        actual ?: run { fail(message ?: "Expected a non-null value") }

    fun <T : Throwable> assertFails(expectedType: Class<T>, block: () -> Unit): T {
        val thrown = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }
        if (thrown == null) fail("Expected ${expectedType.simpleName}, but nothing was thrown")
        if (!expectedType.isInstance(thrown)) {
            fail("Expected ${expectedType.simpleName}, but caught ${thrown::class.simpleName}")
        }
        return expectedType.cast(thrown)
    }
}

object KSLScriptTestRunner {
    /** Runs all cases sequentially and always attempts every teardown hook. */
    fun run(suite: KSLScriptTestSuite): KSLScriptTestRunResult {
        val suiteStarted = System.nanoTime()
        val results = suite.cases.map { testCase ->
            val testStarted = System.nanoTime()
            val context = KSLScriptTestContext()
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
                testCase.name,
                status,
                (System.nanoTime() - testStarted).coerceAtLeast(0L),
                message,
                failure
            )
        }
        return KSLScriptTestRunResult(
            suite.name,
            results,
            (System.nanoTime() - suiteStarted).coerceAtLeast(0L)
        )
    }
}

fun kslTestSuite(name: String, block: KSLScriptTestSuiteBuilder.() -> Unit): KSLScriptTestRunResult =
    KSLScriptTestRunner.run(KSLScriptTestSuiteBuilder(name).apply(block).build())
