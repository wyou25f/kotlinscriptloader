package ru.privateserver.ksl

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable bounded worker pool for script compilation.
 *
 * Saturation applies back-pressure to the submitting thread. It must never fall back to
 * CallerRunsPolicy: a reload initiated on the server thread could otherwise compile there
 * and stall Paper's tick loop.
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
     * Submit only a bounded window at a time. If another batch occupies the queue,
     * wait for capacity instead of executing compilation on the caller.
     */
    fun <T, R> map(items: List<T>, action: (T) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val results = ArrayList<R>(items.size)
        val windowSize = parallelism + queueCapacity
        var offset = 0
        while (offset < items.size) {
            val end = minOf(offset + windowSize, items.size)
            val batch = items.subList(offset, end)
            val futures = ArrayList<Future<R>>(batch.size)
            try {
                batch.forEach { item -> futures += submitBackpressured { action(item) } }
                futures.forEach { results += it.get() }
            } catch (error: Throwable) {
                futures.forEach { it.cancel(true) }
                throw error
            }
            offset = end
        }
        return results
    }

    private fun <R> submitBackpressured(action: () -> R): Future<R> {
        val task = FutureTask(Callable { action() })
        try {
            executor.execute(task)
            return task
        } catch (rejected: RejectedExecutionException) {
            if (executor.isShutdown) throw rejected
        }

        try {
            executor.queue.put(task)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RejectedExecutionException("Interrupted while waiting for KSL compile queue capacity", interrupted)
        }

        // shutdownNow() drains and cancels queued FutureTasks. Remove our task if shutdown
        // raced with queue insertion so the caller cannot wait forever on an unrun Future.
        if (executor.isShutdown && executor.remove(task)) {
            task.cancel(false)
            throw RejectedExecutionException("KSL compile pool is shutting down")
        }
        return task
    }

    override fun close() {
        executor.shutdownNow().forEach { queued ->
            (queued as? Future<*>)?.cancel(true)
        }
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
