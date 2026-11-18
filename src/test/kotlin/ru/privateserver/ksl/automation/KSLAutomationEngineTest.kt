package ru.privateserver.ksl.automation

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.api.KSLAddonScope
import ru.privateserver.ksl.kslshu.KSLOfficialTaskScheduler
import ru.privateserver.ksl.kslshu.KSLTaskScope
import ru.privateserver.ksl.rt.KSLWorkScheduler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KSLAutomationEngineTest {
    private fun newScope(workers: KSLWorkScheduler): KSLAddonScope {
        val plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, _ ->
            if (method.name == "getLogger") java.util.logging.Logger.getLogger("automation-test") else null
        } as Plugin
        return KSLAddonScope(
            plugin,
            { _, _ -> AutoCloseable { } },
            KSLTaskScope(KSLOfficialTaskScheduler(workers), "addon:automation-test")
        )
    }

    @Test
    fun parsesYamlAndTomlIntoTheSameModel() {
        val yaml = """
            version: 1
            automations:
              - id: cache.refresh
                enabled: true
                schedule:
                  type: every
                  interval-ms: 5000
                  initial-delay-ms: 1000
                action:
                  handler: cache.refresh
                  args:
                    source: players
                    limit: 25
        """.trimIndent()
        val toml = """
            version = 1
            [[automations]]
            id = "cache.refresh"
            enabled = true
            [automations.schedule]
            type = "every"
            interval-ms = 5000
            initial-delay-ms = 1000
            [automations.action]
            handler = "cache.refresh"
            [automations.action.args]
            source = "players"
            limit = 25
        """.trimIndent()
        assertEquals(
            KSLAutomationConfigParser.parse(yaml, KSLAutomationFormat.YAML),
            KSLAutomationConfigParser.parse(toml, KSLAutomationFormat.TOML)
        )
    }

    @Test
    fun rejectsDuplicateIdsAndInvalidIntervals() {
        val duplicate = """
            version: 1
            automations:
              - id: repeated
                schedule: { type: once, delay-ms: 100 }
                action: { handler: cache.refresh }
              - id: repeated
                schedule: { type: once, delay-ms: 100 }
                action: { handler: cache.refresh }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            KSLAutomationConfigParser.parse(duplicate, KSLAutomationFormat.YAML)
        }
        val invalid = """
            version: 1
            automations:
              - id: invalid
                schedule: { type: every, interval-ms: 0 }
                action: { handler: cache.refresh }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            KSLAutomationConfigParser.parse(invalid, KSLAutomationFormat.YAML)
        }
    }

    @Test
    fun oneShotRunsAndIsRemovedFromTheActiveSet() {
        val workers = KSLWorkScheduler(1, 16)
        val scope = newScope(workers)
        val registry = KSLAutomationActionRegistry()
        val latch = CountDownLatch(1)
        val engine = scope.automation(registry)
        registry.register("test.signal") { latch.countDown() }
        try {
            val result = engine.reload(
                """
                    version: 1
                    automations:
                      - id: signal
                        schedule: { type: once, delay-ms: 50 }
                        action: { handler: test.signal }
                """.trimIndent(),
                KSLAutomationFormat.YAML
            )
            assertEquals(1, result.active)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (engine.activeIds.isNotEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(engine.activeIds.isEmpty())
        } finally {
            scope.close()
            registry.close()
            workers.close()
        }
    }

    @Test
    fun unknownHandlerDoesNotReplaceExistingAutomation() {
        val workers = KSLWorkScheduler(1, 16)
        val scope = newScope(workers)
        val registry = KSLAutomationActionRegistry()
        registry.register("test.noop") { }
        val engine = scope.automation(registry)
        try {
            engine.reload(
                """
                    version: 1
                    automations:
                      - id: stable
                        schedule: { type: every, interval-ms: 60000 }
                        action: { handler: test.noop }
                """.trimIndent(),
                KSLAutomationFormat.YAML
            )
            assertEquals(setOf("stable"), engine.activeIds)
            assertThrows(IllegalArgumentException::class.java) {
                engine.reload(
                    """
                        version: 1
                        automations:
                          - id: broken
                            schedule: { type: once, delay-ms: 100 }
                            action: { handler: missing.handler }
                    """.trimIndent(),
                    KSLAutomationFormat.YAML
                )
            }
            assertEquals(setOf("stable"), engine.activeIds)
        } finally {
            scope.close()
            registry.close()
            workers.close()
        }
    }

    @Test
    fun addonScopeCloseCancelsRepeatingAutomation() {
        val workers = KSLWorkScheduler(1, 16)
        val scope = newScope(workers)
        val registry = KSLAutomationActionRegistry()
        registry.register("test.noop") { }
        val engine = scope.automation(registry)
        engine.reload(
            """
                version: 1
                automations:
                  - id: heartbeat
                    schedule: { type: every, interval-ms: 60000 }
                    action: { handler: test.noop }
            """.trimIndent(),
            KSLAutomationFormat.YAML
        )
        assertEquals(setOf("heartbeat"), engine.activeIds)
        scope.close()
        assertTrue(engine.activeIds.isEmpty())
        registry.close()
        workers.close()
    }

    @Test
    fun disabledEntriesDoNotRequireRegisteredHandlers() {
        val workers = KSLWorkScheduler(1, 8)
        val scope = newScope(workers)
        val registry = KSLAutomationActionRegistry()
        val engine = scope.automation(registry)
        try {
            val result = engine.reload(
                """
                    version: 1
                    automations:
                      - id: paused
                        enabled: false
                        schedule: { type: every, interval-ms: 1000 }
                        action: { handler: not.registered }
                """.trimIndent(),
                KSLAutomationFormat.YAML
            )
            assertEquals(0, result.active)
            assertEquals(1, result.disabled)
            assertTrue(engine.activeIds.isEmpty())
        } finally {
            scope.close()
            registry.close()
            workers.close()
        }
    }
}
