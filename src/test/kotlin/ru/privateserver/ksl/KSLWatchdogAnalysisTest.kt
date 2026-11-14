package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLWatchdogAnalysisTest {
    @Test
    fun `finds the stable leading frames between snapshots`() {
        assertEquals(
            listOf("Paper.tick", "PluginManager.callEvent"),
            KSLWatchdogAnalysis.commonPrefix(
                listOf("Paper.tick", "PluginManager.callEvent", "ShopListener.onJoin"),
                listOf("Paper.tick", "PluginManager.callEvent", "Database.query")
            )
        )
    }

    @Test
    fun `counts omitted threads without negative values`() {
        assertEquals(12, KSLWatchdogAnalysis.skippedThreads(312, 300))
        assertEquals(0, KSLWatchdogAnalysis.skippedThreads(10, 20))
    }
}