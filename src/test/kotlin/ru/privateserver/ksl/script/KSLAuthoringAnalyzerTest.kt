package ru.privateserver.ksl.script
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLAuthoringAnalyzerTest {
    @Test
    fun `flags blocking and unmanaged patterns with source locations`() {
        val diagnostics = KSLAuthoringAnalyzer.analyze(
            """
            onEvent<PlayerJoinEvent> {
                Thread.sleep(1000)
                runBlocking { refreshRemoteData() }
                while (true) { tick() }
                Bukkit.getScheduler().runTask(plugin) { cleanup() }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("blocking-sleep", "blocking-coroutine", "unbounded-loop", "unmanaged-bukkit-task"),
            diagnostics.map { it.code }
        )
        assertTrue(diagnostics.all { it.line > 0 && it.column > 0 })
    }

    @Test
    fun `warns about raw threads and blocking addon reload`() {
        val diagnostics = KSLAuthoringAnalyzer.analyze(
            """
            Thread { doWork() }.start()
            api.reloadScript("events/join")
            """.trimIndent()
        )
        assertEquals(
            listOf("unmanaged-thread", "blocking-addon-reload"),
            diagnostics.map { it.code }
        )
    }

    @Test
    fun `ignores blocking-looking text inside comments and strings`() {
        val diagnostics = KSLAuthoringAnalyzer.analyze(
            """
            // Thread.sleep(1000)
            val sample = "runBlocking { while (true) {} }"
            /* Executors.newFixedThreadPool(4) */
            println("Bukkit.getScheduler().runTask(plugin) {}")
            """.trimIndent()
        )
        assertTrue(diagnostics.isEmpty())
    }
}
