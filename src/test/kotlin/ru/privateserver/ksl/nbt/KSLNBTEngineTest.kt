package ru.privateserver.ksl.nbt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun documentEditsAreTransactionalAndCopiesAreDetached() {
        val document = KSLNBTEngine.parse("{stats:{level:2}}")
        assertThrows(IllegalStateException::class.java) {
            document.edit {
                setIntAt("stats.level", 9)
                throw IllegalStateException("abort")
            }
        }
        assertEquals(2, document.read { intAt("stats.level") })

        val copy = document.copy()
        copy.edit { setIntAt("stats.level", 7) }
        assertEquals(2, document.read { intAt("stats.level") })
        assertEquals(7, copy.read { intAt("stats.level") })
    }

    @Test
    fun retainedMutableEditorCannotMutatePublishedDocument() {
        val document = KSLNBTEngine.empty()
        lateinit var retained: KSLNBTCompound
        document.edit {
            setString("owner", "first")
            retained = this
        }
        retained.setString("owner", "later")
        assertEquals("first", document.read { string("owner") })
    }

    @Test
    fun customLimitsApplyToParseAndMerge() {
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
