package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLValueCacheTest {
    @Test
    fun invalidatingOneOptimisticWritePreservesOtherCells() {
        val cache = KSLValueCache(16)
        cache.store("row\u0000a", "v:first")
        cache.store("row\u0000b", "v:second")

        cache.invalidate("row\u0000a")

        assertFalse(cache.has("row\u0000a"))
        assertTrue(cache.has("row\u0000b"))
        assertEquals("v:second", cache.value("row\u0000b"))
    }

    @Test
    fun invalidationPreventsAnOlderInflightReadFromRestoringStaleData() {
        val cache = KSLValueCache(16)
        val readVersion = cache.version()

        cache.store("row\u0000key", "v:new")
        cache.invalidate("row\u0000key")
        cache.fill("row\u0000key", "v:stale", readVersion)

        assertFalse(cache.has("row\u0000key"))
    }
}
