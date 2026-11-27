package ru.privateserver.ksl.script

import kotlin.script.experimental.api.ScriptCompilationConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KSLCompilationConfigCacheTest {
    @Test
    fun `same environment reuses one immutable compilation configuration`() {
        val cache = KSLCompilationConfigCache()
        var builds = 0

        val first = cache.getOrBuild("addon-set-a") {
            builds++
            ScriptCompilationConfiguration { }
        }
        val second = cache.getOrBuild("addon-set-a") {
            builds++
            ScriptCompilationConfiguration { }
        }

        assertSame(first, second)
        assertEquals(1, builds)
    }

    @Test
    fun `environment changes replace the prior configuration instead of accumulating entries`() {
        val cache = KSLCompilationConfigCache()
        val first = cache.getOrBuild("library-generation-1") { ScriptCompilationConfiguration { } }
        val second = cache.getOrBuild("library-generation-2") { ScriptCompilationConfiguration { } }

        assertNotSame(first, second)
        assertSame(second, cache.getOrBuild("library-generation-2") { error("cache should hit") })
    }

    @Test
    fun `clear releases the cached configuration entry`() {
        val cache = KSLCompilationConfigCache()
        val first = cache.getOrBuild("env") { ScriptCompilationConfiguration { } }
        cache.clear()
        val second = cache.getOrBuild("env") { ScriptCompilationConfiguration { } }

        assertNotSame(first, second)
    }
}
