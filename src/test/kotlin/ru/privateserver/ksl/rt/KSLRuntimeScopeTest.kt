package ru.privateserver.ksl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KSLRuntimeScopeTest {
    @Test
    fun `scope closes resources in reverse order and only once`() {
        val scope = KSLRuntimeScope("test")
        val order = mutableListOf<Int>()
        scope.own(AutoCloseable { order += 1 })
        scope.onClose { order += 2 }
        scope.own(AutoCloseable { order += 3 })

        scope.close()
        scope.close()

        assertEquals(listOf(3, 2, 1), order)
        assertTrue(scope.isClosed)
        assertEquals(0, scope.ownedResourceCount)
    }

    @Test
    fun `parent scope closes nested scopes in reverse ownership order`() {
        val parent = KSLRuntimeScope("plugin")
        val events = mutableListOf<String>()
        parent.onClose { events += "parent-resource" }

        val child = parent.childScope("plugin/database")
        child.onClose { events += "child-first" }
        child.onClose { events += "child-second" }

        parent.close()

        assertEquals(
            listOf("child-second", "child-first", "parent-resource"),
            events
        )
        assertTrue(child.isClosed)
        assertTrue(parent.isClosed)
        assertEquals(0, parent.ownedResourceCount)
    }

    @Test
    fun `manually closed child detaches from its parent`() {
        val parent = KSLRuntimeScope("plugin")
        val parentCleanupCalls = AtomicInteger()
        parent.onClose { parentCleanupCalls.incrementAndGet() }
        val child = parent.childScope("plugin/cache")

        assertEquals(2, parent.ownedResourceCount)
        child.close()
        child.close()

        assertEquals(1, parent.ownedResourceCount)
        parent.close()
        assertEquals(1, parentCleanupCalls.get())
    }

    @Test
    fun `scope cancels owned scheduler handles when closed`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val scope = KSLRuntimeScope("scheduled")
        try {
            val handle = scope.own(scheduler.scheduleHandle("scheduled", 60L, TimeUnit.SECONDS) { })
            assertEquals(1, scope.ownedResourceCount)

            scope.close()

            assertEquals(KSLWorkState.CANCELLED, handle.await(2, TimeUnit.SECONDS).state)
            assertEquals(0, scope.ownedResourceCount)
        } finally {
            scope.close()
            scheduler.close()
        }
    }

    @Test
    fun `completed scheduler handle releases scope ownership automatically`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val scope = KSLRuntimeScope("completed")
        try {
            val handle = scope.own(scheduler.submitHandle("completed") { })
            assertEquals(KSLWorkState.SUCCEEDED, handle.await(2, TimeUnit.SECONDS).state)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (scope.ownedResourceCount != 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0, scope.ownedResourceCount)
            assertFalse(scope.isClosed)
        } finally {
            scope.close()
            scheduler.close()
        }
    }

    @Test
    fun `closing one child cancels only that childs submitted work`() {
        val executor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val parent = KSLRuntimeScope("plugin")
        val first = parent.childScope("plugin/first")
        val second = parent.childScope("plugin/second")
        val firstStarted = CountDownLatch(1)

        try {
            val firstWork = first.submit(executor) {
                firstStarted.countDown()
                CountDownLatch(1).await()
                "first"
            }
            val secondWork = second.submit(executor) { "second" }

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            first.close()

            assertTrue(firstWork.isCancelled)
            assertEquals("second", secondWork.get(2, TimeUnit.SECONDS))
            assertFalse(parent.isClosed)
            assertFalse(second.isClosed)
        } finally {
            parent.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `child cleanup failure does not block parent or sibling cleanup`() {
        val parent = KSLRuntimeScope("plugin")
        val cleanupCalls = AtomicInteger()
        parent.onClose { cleanupCalls.incrementAndGet() }

        val child = parent.childScope("plugin/worker")
        child.onClose { cleanupCalls.incrementAndGet() }
        child.onClose { throw IllegalStateException("child cleanup failed") }

        val failure = assertThrows(IllegalStateException::class.java) {
            parent.close()
        }

        assertTrue(failure.message!!.contains("failed to close"))
        assertTrue(child.isClosed)
        assertEquals(2, cleanupCalls.get())
        assertTrue(parent.isClosed)
    }

    @Test
    fun `closed parent rejects creation of a child scope`() {
        val parent = KSLRuntimeScope("closed-parent")
        parent.close()

        assertThrows(IllegalStateException::class.java) {
            parent.childScope("orphan")
        }
        assertEquals(0, parent.ownedResourceCount)
    }

    @Test
    fun `scope owns caches and scoped profilers`() {
        val scope = KSLRuntimeScope("shop")
        val cache = scope.lru<String, Int>(4)
        cache["key"] = 42
        val profiler = scope.profile("load")
        assertEquals("ok", profiler { "ok" })
        assertEquals(setOf("load"), scope.profilerNames)
        assertEquals(1L, scope.profileSnapshots().single().calls)

        scope.close()
        assertTrue(cache.isEmpty)
        assertTrue(scope.profilerNames.isEmpty())
        assertTrue(scope.isClosed)
    }

    @Test
    fun `closing registration handle unregisters captured callback`() {
        val scope = KSLRuntimeScope("handles")
        val calls = AtomicInteger()
        val handle = scope.onClose { calls.incrementAndGet() }
        handle.close()
        assertEquals(0, scope.ownedResourceCount)
        scope.close()
        assertEquals(0, calls.get())
    }

    @Test
    fun `resources registered after close are closed immediately and rejected`() {
        val scope = KSLRuntimeScope("closed")
        scope.close()
        val calls = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            scope.own(AutoCloseable { calls.incrementAndGet() })
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `cleanup continues after a resource fails`() {
        val scope = KSLRuntimeScope("failure")
        val calls = AtomicInteger()
        scope.own(AutoCloseable { calls.incrementAndGet() })
        scope.onClose { throw IllegalStateException("broken") }
        scope.own(AutoCloseable { calls.incrementAndGet() })

        val failure = assertThrows(IllegalStateException::class.java) { scope.close() }
        assertTrue(failure.message!!.contains("failed to close"))
        assertEquals(2, calls.get())
        assertTrue(scope.isClosed)
    }

    @Test
    fun `scope bounds retained callbacks and scoped profiler names`() {
        val scope = KSLRuntimeScope("bounded")
        repeat(KSLRuntimeScope.MAX_OWNED_RESOURCES) { scope.onClose {} }
        assertThrows(IllegalStateException::class.java) { scope.onClose {} }
        scope.close()

        val profiles = KSLRuntimeScope("profiles")
        repeat(KSLRuntimeScope.MAX_PROFILERS_PER_SCOPE) { profiles.profiler("p-$it") }
        assertThrows(IllegalStateException::class.java) { profiles.profiler("overflow") }
        profiles.close()
    }

    @Test
    fun submitCompletesAndReleasesItsOwnershipHandle() {
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val scope = KSLRuntimeScope("submitted")
        try {
            val result = scope.submit(executor) { 42 }
            assertEquals(42, result.get(2, TimeUnit.SECONDS))
            assertEquals(0, scope.ownedResourceCount)
        } finally {
            scope.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closingScopeCancelsAndRemovesQueuedWork() {
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val scope = KSLRuntimeScope("queued")
        val started = CountDownLatch(1)
        try {
            executor.execute {
                started.countDown()
                try {
                    CountDownLatch(1).await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val stale = scope.submit(executor) { "must-not-run" }
            assertEquals(1, executor.queue.size)

            scope.close()

            assertTrue(stale.isCancelled)
            assertEquals(0, executor.queue.size)
        } finally {
            scope.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent close waits until all cleanup callbacks finish`() {
        val scope = KSLRuntimeScope("concurrent-close")
        val cleanupStarted = CountDownLatch(1)
        val allowCleanupToFinish = CountDownLatch(1)
        val secondCloseStarted = CountDownLatch(1)
        val firstCloseReturned = CountDownLatch(1)
        val secondCloseReturned = CountDownLatch(1)

        scope.onClose {
            cleanupStarted.countDown()
            allowCleanupToFinish.await()
        }

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

}
