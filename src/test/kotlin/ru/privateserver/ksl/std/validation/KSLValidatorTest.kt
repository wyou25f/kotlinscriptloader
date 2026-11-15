package ru.privateserver.ksl.std.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLValidatorTest {
    @Test
    fun accumulatesStringValidationErrors() {
        val validator = kslValidator(
            notBlank(),
            codePointLength(3, 16),
            matches(Regex("[A-Za-z0-9_]+"))
        )

        val result = "😀".validateWith(validator)
        assertFalse(result.isValid)
        assertEquals(listOf("codePointLength", "matches"), result.errors.map { it.rule })
        assertNull(result.getOrNull())
        assertThrows(IllegalArgumentException::class.java) { result.getOrThrow() }
    }

    @Test
    fun acceptsValidStringsAndCountsUnicodeCodePoints() {
        val validator = kslValidator(notBlank(), codePointLength(2, 4))
        val result = "A😀B".validateWith(validator)

        assertTrue(result.isValid)
        assertEquals("A😀B", result.getOrThrow())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validatesNumericRangesAndRejectsNonFiniteDoubles() {
        val integerValidator = kslValidator(intRange(1..100))
        val doubleValidator = kslValidator(doubleRange(0.0, 1.0))

        assertTrue(integerValidator.validate(50).isValid)
        assertFalse(integerValidator.validate(101).isValid)
        assertTrue(doubleValidator.validate(0.5).isValid)
        assertFalse(doubleValidator.validate(Double.NaN).isValid)
        assertFalse(doubleValidator.validate(Double.POSITIVE_INFINITY).isValid)
        assertFalse(doubleValidator.validate(-0.1).isValid)
    }

    @Test
    fun rulesComposeWithoutMutatingTheOriginalValidator() {
        val base = kslValidator<Int>(intRange(1..10))
        val extended = base.and(validationRule("even", "must be even") { it % 2 == 0 })

        assertTrue(base.validate(3).isValid)
        assertFalse(extended.validate(3).isValid)
        assertTrue(extended.validate(4).isValid)
        assertEquals(listOf("intRange"), base.ruleNames)
        assertEquals(listOf("intRange", "even"), extended.ruleNames)
    }

    @Test
    fun validatesWithNoRulesAndRejectsInvalidRuleDefinitions() {
        assertEquals("anything", kslValidator<String>().validate("anything").getOrThrow())
        assertThrows(IllegalArgumentException::class.java) { codePointLength(-1, 2) }
        assertThrows(IllegalArgumentException::class.java) { codePointLength(3, 2) }
        assertThrows(IllegalArgumentException::class.java) { intRange(5..1) }
        assertThrows(IllegalArgumentException::class.java) { longRange(5L..1L) }
        assertThrows(IllegalArgumentException::class.java) { doubleRange(2.0, 1.0) }
    }
}
