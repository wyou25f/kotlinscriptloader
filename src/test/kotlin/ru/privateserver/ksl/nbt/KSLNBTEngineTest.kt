package ru.privateserver.ksl.nbt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * These tests cover the detached engine's input and path policies without requiring
 * a live CraftBukkit/NMS runtime. Item-NBT-API parsing is exercised by the server
 * integration environment rather than MockBukkit, which does not ship NMS classes.
 */
class KSLNBTEngineTest {
    @Test
    fun validatesSimpleCompoundsArraysAndQuotedDelimiters() {
        KSLNBTSnbtPolicy.validate("""{name:"a,b",stats:{level:3},values:[1,2,3],bytes:[B;1b,2b]}""")
    }

    @Test
    fun rejectsOversizedInputDepthAndEntryCount() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLNBTSnbtPolicy.validate("{value:123}", KSLNBTLimits(maxSnbtCharacters = 8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLNBTSnbtPolicy.validate("{a:{b:{c:1}}}", KSLNBTLimits(maxNestingDepth = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLNBTSnbtPolicy.validate("{items:[1,2,3]}", KSLNBTLimits(maxEntries = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLNBTSnbtPolicy.validate("{a:{b:1,c:2},d:3}", KSLNBTLimits(maxEntries = 3))
        }
    }

    @Test
    fun rejectsUnmatchedContainersAndUnterminatedQuotes() {
        assertThrows(IllegalArgumentException::class.java) { KSLNBTSnbtPolicy.validate("{a:[1,2}") }
        assertThrows(IllegalArgumentException::class.java) { KSLNBTSnbtPolicy.validate("{a:1]]") }
        assertThrows(IllegalArgumentException::class.java) { KSLNBTSnbtPolicy.validate("{a:\"open}") }
    }

    @Test
    fun pathParserAcceptsSafeNestedKeysAndRejectsTraversal() {
        assertEquals(listOf("profile", "stats", "level"), KSLNBTPathPolicy.parse("profile.stats.level"))
        assertThrows(IllegalArgumentException::class.java) { KSLNBTPathPolicy.parse("../secret") }
        assertThrows(IllegalArgumentException::class.java) { KSLNBTPathPolicy.parse("a..b") }
        assertThrows(IllegalArgumentException::class.java) { KSLNBTPathPolicy.parse("a".repeat(513)) }
    }

    @Test
    fun emptyDocumentIsDetachedAndPreflightRejectionIsAtomic() {
        val document = KSLNBTEngine.empty()
        assertEquals("{}", document.snbt())

        assertThrows(IllegalArgumentException::class.java) {
            document.merge("{unfinished")
        }

        assertEquals("{}", document.snbt())
    }

    @Test
    fun customLimitsApplyBeforeParserAndMerge() {
        val limits = KSLNBTLimits(maxSnbtCharacters = 24)
        assertThrows(IllegalArgumentException::class.java) {
            KSLNBTEngine.parse("{a:\"this value is too long\"}", limits)
        }
        val document = KSLNBTEngine.empty(limits)
        assertThrows(IllegalArgumentException::class.java) {
            document.merge("{a:\"this value is too long\"}")
        }
        assertTrue(document.snbt().length <= limits.maxSnbtCharacters)
    }
}
