package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KSLCompilePoolTest {

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
