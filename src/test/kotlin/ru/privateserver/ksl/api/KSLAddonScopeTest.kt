package ru.privateserver.ksl.api

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import ru.privateserver.ksl.kslshu.KSLTaskScope
import ru.privateserver.ksl.kslshu.KSLTaskState
import ru.privateserver.ksl.kslshu.KSLOfficialTaskScheduler
import ru.privateserver.ksl.rt.KSLWorkScheduler
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KSLAddonScopeTest {
    private fun scope(): KSLAddonScope {
        val plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, _, _ -> null } as Plugin
        return KSLAddonScope(plugin, subscribeAction = { _, _ -> AutoCloseable { } })
    }

    @Test
    fun closesResourcesInReverseOrderAndOnlyOnce() {
        val scope = scope()
        val closed = mutableListOf<String>()

        scope.own(AutoCloseable { closed += "first" })
        scope.own(AutoCloseable { closed += "second" })
        assertEquals(2, scope.resourceCount)

        scope.close()
        scope.close()

        assertEquals(listOf("second", "first"), closed)
        assertEquals(0, scope.resourceCount)
        assertTrue(scope.isClosed)
    }

    @Test
    fun releaseClosesOnlyTheRequestedResource() {
        val scope = scope()
        var firstClosed = false
        var secondClosed = false
        val first = AutoCloseable { firstClosed = true }
        val second = AutoCloseable { secondClosed = true }

        scope.own(first)
        scope.own(second)

        assertTrue(scope.release(first))
        assertFalse(scope.release(first))
        assertTrue(firstClosed)
        assertFalse(secondClosed)

        scope.close()
        assertTrue(secondClosed)
    }

    @Test
    fun owningAfterCloseImmediatelyClosesAndRejectsResource() {
        val scope = scope()
        scope.close()
        var released = false

        assertThrows(IllegalStateException::class.java) {
            scope.own(AutoCloseable { released = true })
        }

        assertTrue(released)
        assertTrue(scope.isClosed)
    }

    @Test
    fun addonScopeOwnsTasksCreatedFromFullSpecifications() {
        val workers = KSLWorkScheduler(1, 8)
        val scheduler = KSLTaskScope(KSLOfficialTaskScheduler(workers), "addon:test")
        val plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, _ -> if (method.name == "getLogger") java.util.logging.Logger.getLogger("test") else null } as Plugin
        val scope = KSLAddonScope(plugin, { _, _ -> AutoCloseable { } }, scheduler)
        try {
            val handle = scope.schedule(
                ru.privateserver.ksl.kslshu.KSLTaskSpec("spoofed-owner", delay = Duration.ofHours(1)) { }
            )
            assertEquals("addon:test", handle.owner)
            scope.close()
            assertEquals(KSLTaskState.CANCELLED, handle.completion.get(2, TimeUnit.SECONDS).state)
        } finally {
            workers.close()
        }
    }

    @Test
    fun ownedSchedulerTaskIsCancelledWhenScopeCloses() {
        val workers = KSLWorkScheduler(1, 8)
        val scheduler = KSLTaskScope(KSLOfficialTaskScheduler(workers), "addon:test")
        val plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, _ -> if (method.name == "getLogger") java.util.logging.Logger.getLogger("test") else null } as Plugin
        val scope = KSLAddonScope(plugin, { _, _ -> AutoCloseable { } }, scheduler)
        try {
            val handle = scope.repeat(Duration.ofHours(1), initialDelay = Duration.ofHours(1)) { }
            assertEquals(1, scope.resourceCount)
            scope.close()
            assertEquals(0, scope.resourceCount)
            assertEquals(KSLTaskState.CANCELLED, handle.completion.get(2, TimeUnit.SECONDS).state)
        } finally {
            workers.close()
        }
    }

    @Test
    fun completedOneShotTaskIsRemovedFromScope() {
        val workers = KSLWorkScheduler(1, 8)
        val scheduler = KSLTaskScope(KSLOfficialTaskScheduler(workers), "addon:test")
        val plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, _ -> if (method.name == "getLogger") java.util.logging.Logger.getLogger("test") else null } as Plugin
        val scope = KSLAddonScope(plugin, { _, _ -> AutoCloseable { } }, scheduler)
        try {
            val latch = CountDownLatch(1)
            val handle = scope.schedule { latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            handle.completion.get(2, TimeUnit.SECONDS)
            assertEquals(0, scope.resourceCount)
        } finally {
            scope.close()
            workers.close()
        }
    }

    @Test
    fun closeAttemptsRemainingResourcesAfterOneFails() {
        val scope = scope()
        var secondClosed = false
        scope.own(AutoCloseable { secondClosed = true })
        scope.own(AutoCloseable { error("close failed") })

        assertThrows(IllegalStateException::class.java) { scope.close() }
        assertTrue(secondClosed)
        assertTrue(scope.isClosed)
    }

    @Test
    fun completedOneShotBukkitTasksAreRemovedFromTheAddonScope() {
        val scope = scope()
        var scheduled: Runnable? = null
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val task = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.scheduler.BukkitTask::class.java.classLoader,
            arrayOf(org.bukkit.scheduler.BukkitTask::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> {
                    cancelled.incrementAndGet()
                    null
                }
                "isCancelled" -> cancelled.get() > 0
                else -> null
            }
        } as org.bukkit.scheduler.BukkitTask

        scope.trackOneShot(
            schedule = { runnable ->
                scheduled = runnable
                task
            },
            block = { }
        )
        assertEquals(1, scope.resourceCount)

        requireNotNull(scheduled).run()

        assertEquals(0, scope.resourceCount)
        scope.close()
        assertEquals(0, cancelled.get(), "completed tasks should not be cancelled during addon unload")
    }

    @Test
    fun closingScopeBeforeOneShotTaskRunsCancelsItsScheduledHandle() {
        val scope = scope()
        var scheduled: Runnable? = null
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val task = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.scheduler.BukkitTask::class.java.classLoader,
            arrayOf(org.bukkit.scheduler.BukkitTask::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> {
                    cancelled.incrementAndGet()
                    null
                }
                "isCancelled" -> cancelled.get() > 0
                else -> null
            }
        } as org.bukkit.scheduler.BukkitTask

        scope.trackOneShot(
            schedule = { runnable ->
                scheduled = runnable
                task
            },
            block = { }
        )
        scope.close()

        assertEquals(1, cancelled.get())
        assertEquals(0, scope.resourceCount)
        // A scheduler race after cancellation must still be harmless and release idempotently.
        requireNotNull(scheduled).run()
        assertEquals(0, scope.resourceCount)
    }


    @Test
    fun childScopesCloseTheirResourcesWithTheParent() {
        val parent = scope()
        val child = parent.childScope()
        val closed = mutableListOf<String>()
        child.own(AutoCloseable { closed += "child-resource" })
        parent.own(AutoCloseable { closed += "parent-resource" })

        assertEquals(2, parent.resourceCount)
        parent.close()

        assertEquals(listOf("parent-resource", "child-resource"), closed)
        assertTrue(child.isClosed)
        assertEquals(0, child.resourceCount)
    }

    @Test
    fun typedSubscriptionsAreOwnedByTheScope() {
        var callback: ((Any?) -> Unit)? = null
        var closed = false
        val plugin = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, _, _ -> null } as Plugin
        val scope = KSLAddonScope(plugin, { _, handler ->
            callback = handler
            AutoCloseable { closed = true; callback = null }
        })
        var received: String? = null
        scope.subscribe(kslTopic<String>("test.typed")) { received = it }
        callback?.invoke("payload")
        assertEquals("payload", received)
        scope.close()
        assertTrue(closed)
        assertNull(callback)
    }

    @Test
    fun concurrentCloseWaitsUntilEveryOwnedResourceHasFinishedClosing() {
        val scope = scope()
        val cleanupStarted = CountDownLatch(1)
        val allowCleanupToFinish = CountDownLatch(1)
        val secondCloseStarted = CountDownLatch(1)
        val firstCloseReturned = CountDownLatch(1)
        val secondCloseReturned = CountDownLatch(1)

        scope.own(AutoCloseable {
            cleanupStarted.countDown()
            allowCleanupToFinish.await()
        })

        val firstCloser = Thread {
            scope.close()
            firstCloseReturned.countDown()
        }
        val secondCloser = Thread {
            secondCloseStarted.countDown()
            scope.close()
            secondCloseReturned.countDown()
        }

        firstCloser.start()
        try {
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            secondCloser.start()
            assertTrue(secondCloseStarted.await(2, TimeUnit.SECONDS))
            assertFalse(secondCloseReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowCleanupToFinish.countDown()
        }

        assertTrue(firstCloseReturned.await(2, TimeUnit.SECONDS))
        assertTrue(secondCloseReturned.await(2, TimeUnit.SECONDS))
        firstCloser.join(2_000)
        secondCloser.join(2_000)
        assertFalse(firstCloser.isAlive)
        assertFalse(secondCloser.isAlive)
    }

    @Test
    fun reentrantCloseFromOwnedResourceDoesNotDeadlock() {
        val scope = scope()
        var laterResourceClosed = false
        scope.own(AutoCloseable { scope.close() })
        scope.own(AutoCloseable { laterResourceClosed = true })

        scope.close()

        assertTrue(laterResourceClosed)
        assertTrue(scope.isClosed)
        assertEquals(0, scope.resourceCount)
    }

}
