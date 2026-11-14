package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KSLPersistMemoryCacheTest {
    @Test
    fun failedDatabaseReadCachesTheDefaultUntilTheScriptIsCleared() {
        val cache = KSLPersistMemoryCache()
        var loadAttempts = 0
        var defaultCalls = 0

        repeat(3) {
            assertEquals(25, cache.get("shop:coins", {
                defaultCalls++
                25
            }) {
                loadAttempts++
                null
            })
        }

        assertEquals(1, loadAttempts)
        assertEquals(1, defaultCalls)

        cache.clearScript("shop")
        assertEquals(40, cache.get("shop:coins", { 25 }) {
            loadAttempts++
            true to 40
        })
        assertEquals(2, loadAttempts)
    }

    @Test
    fun persistedNullIsCachedAndDoesNotBecomeTheDefault() {
        val cache = KSLPersistMemoryCache()
        var loadAttempts = 0

        assertNull(cache.get<String?>("profile:nickname", { "guest" }) {
            loadAttempts++
            true to null
        })
        assertNull(cache.get<String?>("profile:nickname", { "guest" }) {
            loadAttempts++
            false to "unused"
        })

        assertEquals(1, loadAttempts)
    }

    @Test
    fun clearingOneScriptDoesNotClearAnotherScriptsKeys() {
        val cache = KSLPersistMemoryCache()
        cache.set("shop:coins", 10)
        cache.set("shopper:coins", 20)

        cache.clearScript("shop")

        assertEquals(1, cache.get("shop:coins", { 1 }) { null })
        assertEquals(20, cache.get("shopper:coins", { 2 }) { null })
    }
}
