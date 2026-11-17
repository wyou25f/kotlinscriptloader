package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KSLExecutorShutdownTest {
    @Test
    fun `idle executor shuts down gracefully`() {
        val executor = Executors.newSingleThreadExecutor()

        val result = KSLExecutorShutdown.shutdown(executor, 1, TimeUnit.SECONDS)

        assertTrue(result.terminated)
        assertEquals(0, result.droppedTasks)
        assertFalse(result.interrupted)
    }

    @Test
    fun `timeout interrupts active task and reports queued tasks`() {
        val executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute {
                started.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            executor.execute { }
            executor.execute { }

            val result = KSLExecutorShutdown.shutdown(executor, 5, TimeUnit.MILLISECONDS)

            assertFalse(result.interrupted)
            assertEquals(2, result.droppedTasks)
            assertTrue(executor.isShutdown)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted shutdown forces cancellation and restores interrupt flag`() {
        val executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resultRef = AtomicReference<KSLExecutorShutdown.Result?>()
        val interruptRestored = AtomicReference(false)
        try {
            executor.execute {
                started.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            executor.execute { }

            val shutdownThread = Thread {
                Thread.currentThread().interrupt()
                resultRef.set(KSLExecutorShutdown.shutdown(executor, 5, TimeUnit.SECONDS))
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
            shutdownThread.start()
            shutdownThread.join(2_000)

            assertFalse(shutdownThread.isAlive, "interrupted shutdown must not block")
            val result = requireNotNull(resultRef.get())
            assertTrue(result.interrupted)
            assertEquals(1, result.droppedTasks)
            assertTrue(interruptRestored.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
