package ru.privateserver.ksl.std.validation

/**
 * One failed validation rule. [rule] is a stable identifier suitable for
 * mapping to a localized message; [message] is a developer-facing explanation.
 */
data class KSLValidationIssue(
    val rule: String,
    val message: String
)

/** Result of validating a value without throwing for ordinary invalid input. */
sealed interface KSLValidationResult<out T> {
    data class Valid<T>(val value: T) : KSLValidationResult<T>

    data class Invalid(val issues: List<KSLValidationIssue>) : KSLValidationResult<Nothing> {
        init {
            require(issues.isNotEmpty()) { "Invalid result must contain at least one issue" }
        }
    }

    val isValid: Boolean
        get() = this is Valid<*>

    val errors: List<KSLValidationIssue>
        get() = when (this) {
            is Valid -> emptyList()
            is Invalid -> issues
        }

    fun getOrNull(): T? = when (this) {
        is Valid -> value
        is Invalid -> null
    }

    /** Returns the value or throws an IllegalArgumentException containing every issue. */
    fun getOrThrow(): T = when (this) {
        is Valid -> value
        is Invalid -> throw IllegalArgumentException(
            issues.joinToString("; ") { "${it.rule}: ${it.message}" }
        )
    }

    fun <R> map(transform: (T) -> R): KSLValidationResult<R> = when (this) {
        is Valid -> Valid(transform(value))
        is Invalid -> this
    }
}

/** A named, reusable predicate used by [KSLValidator]. */
data class KSLValidationRule<T>(
    val name: String,
    val message: String,
    val predicate: (T) -> Boolean
) {
    init {
        require(name.isNotBlank()) { "Validation rule name must not be blank" }
        require(message.isNotBlank()) { "Validation rule message must not be blank" }
    }
}

/** Runs a set of rules and accumulates all failures instead of stopping at the first one. */
class KSLValidator<T> private constructor(
    private val rules: List<KSLValidationRule<T>>
) {
    val ruleNames: List<String>
        get() = rules.map { it.name }

    fun validate(value: T): KSLValidationResult<T> {
        val issues = rules.mapNotNull { rule ->
            if (rule.predicate(value)) null else KSLValidationIssue(rule.name, rule.message)
        }
        return if (issues.isEmpty()) {
            KSLValidationResult.Valid(value)
        } else {
            KSLValidationResult.Invalid(issues)
        }
    }

    fun and(rule: KSLValidationRule<T>): KSLValidator<T> =
        KSLValidator(rules + rule)

    fun and(other: KSLValidator<T>): KSLValidator<T> =
        KSLValidator(rules + other.rules)

    companion object {
        @JvmStatic
        fun <T> of(vararg rules: KSLValidationRule<T>): KSLValidator<T> =
            KSLValidator(rules.toList())
    }
}

/** Creates a custom validation rule for any value type. */
fun <T> validationRule(
    name: String,
    message: String,
    predicate: (T) -> Boolean
): KSLValidationRule<T> = KSLValidationRule(name, message, predicate)

/** Rejects blank strings. */
fun notBlank(message: String = "must not be blank"): KSLValidationRule<String> =
    KSLValidationRule("notBlank", message) { it.isNotBlank() }

/** Checks Unicode code-point length, not UTF-16 code-unit length. */
fun codePointLength(
    minimum: Int = 0,
    maximum: Int = Int.MAX_VALUE,
    message: String = "length must be between $minimum and $maximum code points"
): KSLValidationRule<String> {
    require(minimum >= 0 && maximum >= minimum) { "invalid string length bounds" }
    return KSLValidationRule("codePointLength", message) {
        val length = it.codePointCount(0, it.length)
        length in minimum..maximum
    }
}

/** Requires the complete string to match [pattern]. */
fun matches(
    pattern: Regex,
    message: String = "must match ${pattern.pattern}"
): KSLValidationRule<String> =
    KSLValidationRule("matches", message) { pattern.matches(it) }

/** Checks an integer against an inclusive range. */
fun intRange(
    range: IntRange,
    message: String = "must be in the range ${range.first}..${range.last}"
): KSLValidationRule<Int> {
    require(range.first <= range.last) { "integer range must not be empty" }
    return KSLValidationRule("intRange", message) { it in range }
}

/** Checks a long against an inclusive range. */
fun longRange(
    range: LongRange,
    message: String = "must be in the range ${range.first}..${range.last}"
): KSLValidationRule<Long> {
    require(range.first <= range.last) { "long range must not be empty" }
    return KSLValidationRule("longRange", message) { it in range }
}

/** Checks that a double is finite and lies in an inclusive range. */
fun doubleRange(
    minimum: Double,
    maximum: Double,
    message: String = "must be finite and in the range [$minimum, $maximum]"
): KSLValidationRule<Double> {
    require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum) {
        "double bounds must be finite and minimum must not exceed maximum"
    }
    return KSLValidationRule("doubleRange", message) {
        it.isFinite() && it >= minimum && it <= maximum
    }
}

/** Builds a validator from the supplied rules. */
fun <T> kslValidator(vararg rules: KSLValidationRule<T>): KSLValidator<T> =
    KSLValidator.of(*rules)

/** Validates the receiver with [validator]. */
fun <T> T.validateWith(validator: KSLValidator<T>): KSLValidationResult<T> =
    validator.validate(this)
