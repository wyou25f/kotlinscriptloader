package ru.privateserver.ksl.intellij.inspection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KslAsyncThreadSafetyAnalyzerTest {
    @Test
    void detectsBukkitCallsInsideAsyncSchedulerBlocks() {
        List<KslAsyncThreadSafetyAnalyzer.Diagnostic> diagnostics =
            KslAsyncThreadSafetyAnalyzer.analyze("""
                runAsync {
                    val player = Bukkit.getPlayer("Alex")
                    player?.sendMessage("Hello")
                }
                """);

        assertEquals(2, diagnostics.size());
        assertEquals("getPlayer", diagnostics.get(0).method());
        assertEquals("sendMessage", diagnostics.get(1).method());
        assertEquals("runAsync", diagnostics.get(0).asyncFunction());
    }

    @Test
    void detectsNestedAsyncBlocksAndPaperWorldMutationsOnlyOnce() {
        List<KslAsyncThreadSafetyAnalyzer.Diagnostic> diagnostics =
            KslAsyncThreadSafetyAnalyzer.analyze("""
                afterAsync(20) {
                    repeatAsync(5) {
                        world.getBlockAt(1, 2, 3).setType(Material.STONE)
                    }
                }
                """);

        assertEquals(2, diagnostics.size());
        assertEquals("getBlockAt", diagnostics.get(0).method());
        assertEquals("setType", diagnostics.get(1).method());
    }

    @Test
    void ignoresCommentsAndStringLiterals() {
        List<KslAsyncThreadSafetyAnalyzer.Diagnostic> diagnostics =
            KslAsyncThreadSafetyAnalyzer.analyze("""
                // runAsync { Bukkit.getPlayer("comment") }
                val text = "runAsync { Bukkit.getPlayer(\\\"string\\\") }"
                runAsync {
                    // player.sendMessage("comment")
                    println("Bukkit.getOnlinePlayers()")
                }
                """);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void ignoresSynchronousCode() {
        List<KslAsyncThreadSafetyAnalyzer.Diagnostic> diagnostics =
            KslAsyncThreadSafetyAnalyzer.analyze("""
                Bukkit.getPlayer("Alex")?.sendMessage("Hello")
                """);

        assertTrue(diagnostics.isEmpty());
    }
}
