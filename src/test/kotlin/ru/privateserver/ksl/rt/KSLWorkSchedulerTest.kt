package ru.privateserver.ksl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class KSLWorkSchedulerTest {
    @Test
    fun `runs submitted work and tracks completion`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        try {
            val done = CountDownLatch(1)
            scheduler.submit("shop") { done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (scheduler.snapshot().completed != 1L && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0, scheduler.snapshot().tracked)
            assertEquals(1L, scheduler.snapshot().completed)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `enforces per script concurrency limit`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4, perScriptLimit = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            scheduler.submit("shop") {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertThrows(RejectedExecutionException::class.java) {
                scheduler.submit("shop") { }
            }
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun `a script cannot cancel another scripts worker task`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockerFinished = CountDownLatch(1)
        val targetRan = AtomicBoolean(false)
        try {
            scheduler.submit("alpha") {
                started.countDown()
                try {
                    release.await(2, TimeUnit.SECONDS)
                } finally {
                    blockerFinished.countDown()
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val targetId = scheduler.submit("beta") { targetRan.set(true) }
            assertEquals(false, scheduler.cancel("alpha", targetId))
            assertTrue(scheduler.cancel("beta", targetId))

            release.countDown()
            assertTrue(blockerFinished.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (scheduler.snapshot().tracked != 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(false, targetRan.get())
            assertEquals(0, scheduler.snapshot().tracked)
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun `owner task index scopes task listing and cancellation`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val betaRan = CountDownLatch(1)
        try {
            scheduler.submit("blocker") {
                blockerStarted.countDown()
                releaseBlocker.await(2, TimeUnit.SECONDS)
            }
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))

            val alphaId = scheduler.submit("alpha") { error("cancelled alpha task must not run") }
            scheduler.submit("beta") { betaRan.countDown() }

            assertEquals(listOf(alphaId), scheduler.taskInfos("alpha").map { it.id })
            assertEquals(1, scheduler.cancelOwner("alpha"))
            assertEquals(KSLWorkState.CANCELLED, scheduler.taskInfo(alphaId)?.state)
            assertEquals(0, scheduler.cancelOwner("alpha"), "finished owners must leave no stale task-index entries")

            releaseBlocker.countDown()
            assertTrue(betaRan.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (scheduler.snapshot().tracked != 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0, scheduler.snapshot().tracked)
            assertEquals(KSLWorkState.CANCELLED, scheduler.taskInfos("alpha").single().state)
        } finally {
            releaseBlocker.countDown()
            scheduler.close()
        }
    }

    @Test
    fun `per owner quota admits only the configured number under contention`() {
        val quota = KSLPerOwnerQuota(limit = 3)
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(12)
        val release = CountDownLatch(1)
        val admitted = AtomicInteger()
        val executor = Executors.newFixedThreadPool(12)

        try {
            val results = (0 until 12).map {
                executor.submit<Boolean> {
                    start.await()
                    val accepted = quota.acquire("shared")
                    if (accepted) admitted.incrementAndGet()
                    attempted.countDown()
                    if (accepted) {
                        try {
                            release.await(5, TimeUnit.SECONDS)
                        } finally {
                            quota.release("shared")
                        }
                    }
                    accepted
                }
            }

            start.countDown()
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertEquals(3, admitted.get())
            release.countDown()
            assertEquals(3, results.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(quota.acquire("shared"))
            quota.release("shared")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `executes queued tasks by priority and preserves FIFO within a priority`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 8)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(4)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            scheduler.submit("blocker") { started.countDown(); release.await(2, TimeUnit.SECONDS) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            scheduler.submit("shop", KSLWorkPriority.LOW) { order += "low"; finished.countDown() }
            scheduler.submit("shop", KSLWorkPriority.NORMAL) { order += "normal-1"; finished.countDown() }
            scheduler.submit("shop", KSLWorkPriority.HIGH) { order += "high"; finished.countDown() }
            scheduler.submit("shop", KSLWorkPriority.NORMAL) { order += "normal-2"; finished.countDown() }
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("high", "normal-1", "normal-2", "low"), order)
        } finally { release.countDown(); scheduler.close() }
    }

    @Test
    fun `close tolerates tasks completing while cancellation is in progress`() {
        repeat(24) {
            val scheduler = KSLWorkScheduler(workers = 4, queueCapacity = 8)
            val ready = CountDownLatch(4)
            val release = CountDownLatch(1)
            repeat(4) {
                scheduler.submit("closing") {
                    ready.countDown()
                    try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val closer = Thread { runCatching { scheduler.close() }.onFailure(failure::set) }
            closer.start()
            release.countDown()
            closer.join(2_000)
            assertFalse(closer.isAlive, "scheduler.close() should finish promptly")
            assertEquals(null, failure.get())
        }
    }

    @Test
    fun `delayed worker handle exposes completion and result state`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val ran = AtomicBoolean(false)
        try {
            val handle = scheduler.scheduleHandle("shop", 20L) { ran.set(true) }
            val result = handle.await(2, TimeUnit.SECONDS)
            assertEquals(KSLWorkState.SUCCEEDED, result.state)
            assertTrue(ran.get())
            assertEquals(KSLWorkState.SUCCEEDED, handle.info()?.state)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `fixed rate handle can be cancelled and awaited`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val started = CountDownLatch(1)
        val executions = AtomicInteger()
        try {
            val handle = scheduler.scheduleAtFixedRateHandle("periodic", 0L, 10L) {
                executions.incrementAndGet()
                started.countDown()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(handle.cancel())
            val result = handle.await(2, TimeUnit.SECONDS)
            assertEquals(KSLWorkState.CANCELLED, result.state)
            assertTrue(executions.get() >= 1)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `graceful shutdown stops active repeating work without interrupting it`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executions = AtomicInteger()
        val shutdownExecutor = Executors.newSingleThreadExecutor()
        try {
            val handle = scheduler.scheduleAtFixedRateHandle("periodic", 0L, 10L) {
                executions.incrementAndGet()
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val shutdown = shutdownExecutor.submit<Boolean> {
                scheduler.shutdownGracefully(2, TimeUnit.SECONDS)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!scheduler.snapshot().shutdown && System.nanoTime() < deadline) Thread.yield()
            assertTrue(scheduler.snapshot().shutdown)
            release.countDown()
            assertTrue(shutdown.get(2, TimeUnit.SECONDS))
            assertEquals(KSLWorkState.CANCELLED, handle.await(2, TimeUnit.SECONDS).state)
            assertEquals(1, executions.get())
        } finally {
            release.countDown()
            shutdownExecutor.shutdownNow()
            scheduler.close()
        }
    }

    @Test
    fun `worker threads use readable names`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        val done = CountDownLatch(1)
        val threadName = java.util.concurrent.atomic.AtomicReference<String>()
        try {
            scheduler.submit("diagnostics") {
                threadName.set(Thread.currentThread().name)
                done.countDown()
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(threadName.get().startsWith("KSL-Worker-"))
            assertFalse(threadName.get().contains("@@"))
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `resizes worker count within bounds`() {
        val scheduler = KSLWorkScheduler(workers = 1, queueCapacity = 4)
        try {
            scheduler.resize(2)
            assertEquals(2, scheduler.snapshot().workers)
            assertThrows(IllegalArgumentException::class.java) { scheduler.resize(0) }
        } finally {
            scheduler.close()
        }
    }
}
