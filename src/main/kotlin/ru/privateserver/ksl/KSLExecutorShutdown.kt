package ru.privateserver.ksl

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Performs a bounded, interruption-safe shutdown of an executor.
 *
 * A timeout or interruption triggers shutdownNow(). The interrupt flag is restored before
 * returning so plugin shutdown does not silently consume the caller's cancellation signal.
 * droppedTasks counts queued tasks that never started; it does not count the active task.
 */
internal object KSLExecutorShutdown {
    data class Result(
        val terminated: Boolean,
        val droppedTasks: Int,
        val interrupted: Boolean
    )

    fun shutdown(
        executor: ExecutorService,
        timeout: Long,
        unit: TimeUnit
    ): Result {
        require(timeout >= 0L) { "timeout must not be negative" }

        executor.shutdown()
        return try {
            if (executor.awaitTermination(timeout, unit)) {
                Result(terminated = true, droppedTasks = 0, interrupted = false)
            } else {
                val dropped = executor.shutdownNow().size
                Result(terminated = executor.isTerminated, droppedTasks = dropped, interrupted = false)
            }
        } catch (_: InterruptedException) {
            val dropped = try {
                executor.shutdownNow().size
            } finally {
                Thread.currentThread().interrupt()
            }
            Result(terminated = executor.isTerminated, droppedTasks = dropped, interrupted = true)
        }
    }
}
