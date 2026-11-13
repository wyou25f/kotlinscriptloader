package ru.privateserver.ksl

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable bounded worker pool for script compilation.
 *
 * Creating a new executor for every reload causes avoidable thread churn and classloader
 * pressure on servers that reload scripts frequently.
 */
internal class KSLCompilePool(threads: Int) : AutoCloseable {
    private val threadNumber = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(
        threads.coerceIn(1, 8),
        ThreadFactory { task ->
            Thread(task, "KSL-Compile-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        }
    )

    fun <T, R> map(items: List<T>, action: (T) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val futures = items.map { item -> executor.submit(Callable { action(item) }) }
        return try {
            futures.map { it.get() }
        } catch (error: Throwable) {
            futures.forEach { it.cancel(true) }
            throw error
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
