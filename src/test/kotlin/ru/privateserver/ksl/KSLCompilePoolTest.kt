package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class KSLCompilePoolTest {

    @Test
    fun `large reload batches preserve order while being processed in bounded windows`() {
        KSLCompilePool(2).use { pool ->
            val values = pool.map((1..20_000).toList()) { it * 3 }
            assertEquals((1..20_000).map { it * 3 }, values)
        }
    }


    @Test
    fun `queue saturation never executes compile work on the submitting thread`() {
        KSLCompilePool(1).use { pool ->
            val firstTaskStarted = CountDownLatch(1)
            val releaseFirstTask = CountDownLatch(1)
            val callerRanCompile = java.util.concurrent.atomic.AtomicBoolean(false)
            val firstFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val secondFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

            val firstBatch = Thread({
                runCatching {
                    pool.map((1..5).toList()) { value ->
                        if (value == 1) {
                            firstTaskStarted.countDown()
                            check(releaseFirstTask.await(3, TimeUnit.SECONDS))
                        }
                        value
                    }
                }.onFailure(firstFailure::set)
            }, "validation-batch")
            firstBatch.start()
            assertTrue(firstTaskStarted.await(2, TimeUnit.SECONDS))

            val secondBatch = Thread({
                runCatching {
                    pool.map((6..10).toList()) { value ->
                        if (!Thread.currentThread().name.startsWith("KSL-Compile-")) {
                            callerRanCompile.set(true)
                        }
                        value
                    }
                }.onFailure(secondFailure::set)
            }, "server-command-thread")
            secondBatch.start()

            // The first worker is blocked and the bounded queue is full. The second submitter
            // must wait for capacity, not run the compilation itself.
            secondBatch.join(100)
            assertTrue(secondBatch.isAlive, "the saturated submitter should be back-pressured")
            assertTrue(!callerRanCompile.get(), "compile work must never run on the submitting thread")

            releaseFirstTask.countDown()
            firstBatch.join(3_000)
            secondBatch.join(3_000)
            assertTrue(!firstBatch.isAlive)
            assertTrue(!secondBatch.isAlive)
            assertEquals(null, firstFailure.get())
            assertEquals(null, secondFailure.get())
            assertTrue(!callerRanCompile.get())
        }
    }

    @Test
    fun `server thread fails fast when the compile queue is saturated`() {
        KSLCompilePool(1).use { pool ->
            val firstTaskStarted = CountDownLatch(1)
            val releaseFirstTask = CountDownLatch(1)
            val callerRanCompile = AtomicBoolean(false)
            val firstBatch = Thread({
                pool.map((1..5).toList()) { value ->
                    if (value == 1) {
                        firstTaskStarted.countDown()
                        check(releaseFirstTask.await(3, TimeUnit.SECONDS))
                    }
                    value
                }
            }, "background-compile-batch")
            firstBatch.start()
            assertTrue(firstTaskStarted.await(2, TimeUnit.SECONDS))

            assertThrows(RejectedExecutionException::class.java) {
                pool.map((6..10).toList(), waitWhenSaturated = false) { value ->
                    callerRanCompile.set(true)
                    value
                }
            }
            assertFalse(callerRanCompile.get(), "saturated server-thread submission must not run compilation inline")
            releaseFirstTask.countDown()
            firstBatch.join(3_000)
            assertFalse(firstBatch.isAlive)
        }
    }

    @Test
    fun `reuses worker threads across batches and respects configured parallelism`() {
        KSLCompilePool(2).use { pool ->
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()
            val workers = ConcurrentHashMap.newKeySet<Thread>()
            val firstBatchGate = CountDownLatch(2)

            val first = pool.map((1..8).toList()) {
                workers += Thread.currentThread()
                val now = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, now) }
                firstBatchGate.countDown()
                try {
                    assertTrue(firstBatchGate.await(2, TimeUnit.SECONDS), "both compile workers should become active")
                    it * 2
                } finally {
                    active.decrementAndGet()
                }
            }

            assertEquals((1..8).map { it * 2 }, first)
            assertEquals(2, maximumActive.get(), "the pool should run at most two tasks at once")
            val workerSnapshot = workers.toSet()

            val second = pool.map((9..16).toList()) {
                workers += Thread.currentThread()
                it * 2
            }

            assertEquals((9..16).map { it * 2 }, second)
            assertEquals(workerSnapshot, workers, "the second batch should reuse the same worker threads")
            assertTrue(workers.size <= 2)
        }
    }
}
