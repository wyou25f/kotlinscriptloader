package ru.privateserver.ksl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
