package ru.privateserver.ksl.dsl

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class KSLAdvancedDslTest {
    @Test
    fun commandDslRequiresExactlyOneExecutorAndValidName() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLCommandDslBuilder("bad name").apply { player { _, _ -> } }.validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLCommandDslBuilder("valid").validate()
        }
    }

    @Test
    fun commandDslRejectsConflictingExecutorsAndCompleters() {
        assertThrows(IllegalStateException::class.java) {
            KSLCommandDslBuilder("valid").apply {
                player { _, _ -> }
                any { _, _ -> }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            KSLCommandDslBuilder("valid").apply {
                tabComplete { _, _ -> emptyList() }
                tabCompleteAny { _, _ -> emptyList() }
            }
        }
    }

    @Test
    fun commandDslAcceptsUniqueValidAliases() {
        assertDoesNotThrow {
            KSLCommandDslBuilder("balance").apply {
                aliases("bal", "money", "bal")
                player { _, _ -> }
            }.validate()
        }
    }
}
