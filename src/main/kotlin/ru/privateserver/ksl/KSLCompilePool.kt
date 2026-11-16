package ru.privateserver.ksl

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable bounded worker pool for script compilation.
 *
 * Creating a new executor for every reload causes avoidable thread churn and classloader
 * pressure on servers that reload scripts frequently.
 */
internal class KSLCompilePool(threads: Int) : AutoCloseable {
    private val threadNumber = AtomicInteger()
    private val parallelism = threads.coerceIn(1, 8)
    private val queueCapacity = parallelism * 4
    private val executor = ThreadPoolExecutor(
        parallelism,
        parallelism,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        ThreadFactory { task ->
            Thread(task, "KSL-Compile-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    /**
     * Submit only a bounded window at a time. A fixed thread pool backed by an unbounded
     * queue can retain one Future and one task closure for every script in a large reload.
     */
    fun <T, R> map(items: List<T>, action: (T) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val results = ArrayList<R>(items.size)
        val windowSize = parallelism + queueCapacity
        items.chunked(windowSize).forEach { batch ->
            val futures = batch.map { item -> executor.submit(Callable { action(item) }) }
            try {
                futures.forEach { results += it.get() }
            } catch (error: Throwable) {
                futures.forEach { it.cancel(true) }
                throw error
            }
        }
        return results
    }

    override fun close() {
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                // Compiler tasks are interruptible where possible; the daemon pool must not hold shutdown open.
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
