package ru.privateserver.ksl.rt

import org.junit.jupiter.api.Assertions.assertEquals
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
