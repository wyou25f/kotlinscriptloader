package ru.privateserver.ksl.rt

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class KSLWorkPriority(val rank: Int) {
    LOW(0), NORMAL(1), HIGH(2)
}

data class KSLWorkSnapshot(
    val workers: Int,
    val active: Int,
    val queued: Int,
    val tracked: Int,
    val completed: Long,
    val rejected: Long
)

/**
 * Bounded, priority-aware worker scheduler for script-owned CPU/background work.
 * It never runs rejected work on the caller thread (especially the Bukkit main thread).
 */
class KSLWorkScheduler(
    workers: Int,
    queueCapacity: Int,
    private val perScriptLimit: Int = 64
) : AutoCloseable {
    private val ids = AtomicLong()
    private val sequence = AtomicLong()
    private val threadIds = AtomicInteger()
    private val closed = AtomicBoolean()
    private val rejected = AtomicLong()
    private val permits = Semaphore(workers.coerceAtLeast(1) + queueCapacity.coerceAtLeast(1))
    private val ownerCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val tasks = ConcurrentHashMap<Long, WorkTask>()

    private val executor = ThreadPoolExecutor(
        workers.coerceIn(1, 64),
        workers.coerceIn(1, 64),
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "KSL-Worker-${threadIds.incrementAndGet()}").apply { isDaemon = true }
        }
    )

    private inner class WorkTask(
        val id: Long,
        val owner: String,
        val priority: KSLWorkPriority,
        private val order: Long,
        private val action: () -> Unit
    ) : Runnable, Comparable<WorkTask> {
        private val finished = AtomicBoolean()
        @Volatile private var runner: Thread? = null
        @Volatile var cancelled = false
            private set

        override fun compareTo(other: WorkTask): Int {
            val byPriority = other.priority.rank.compareTo(priority.rank)
            return if (byPriority != 0) byPriority else order.compareTo(other.order)
        }

        override fun run() {
            runner = Thread.currentThread()
            try {
                if (!cancelled && !closed.get()) action()
            } finally {
                runner = null
                finish()
            }
        }

        fun cancel() {
            cancelled = true
            runner?.interrupt()
            if (executor.remove(this)) finish()
        }

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            tasks.remove(id)
            ownerCounts[owner]?.let { count ->
                if (count.decrementAndGet() <= 0) ownerCounts.remove(owner, count)
            }
            permits.release()
        }
    }

    init {
        require(workers in 1..64) { "workers must be between 1 and 64" }
        require(queueCapacity in 1..100_000) { "queueCapacity must be between 1 and 100000" }
        require(perScriptLimit > 0) { "perScriptLimit must be positive" }
    }

    fun submit(owner: String, priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit): Long {
        require(owner.isNotBlank()) { "owner must not be blank" }
        check(!closed.get()) { "KSL work scheduler is closed" }

        val count = ownerCounts.computeIfAbsent(owner) { AtomicInteger() }
        if (count.incrementAndGet() > perScriptLimit) {
            count.decrementAndGet()
            rejected.incrementAndGet()
            throw RejectedExecutionException("Script '$owner' exceeded its KSL worker task limit ($perScriptLimit)")
        }
        if (!permits.tryAcquire()) {
            count.decrementAndGet()
            if (count.get() <= 0) ownerCounts.remove(owner, count)
            rejected.incrementAndGet()
            throw RejectedExecutionException("KSL worker queue is full")
        }

        val id = ids.incrementAndGet()
        val task = WorkTask(id, owner, priority, sequence.incrementAndGet(), action)
        tasks[id] = task
        try {
            executor.execute(task)
        } catch (error: Throwable) {
            task.finish()
            rejected.incrementAndGet()
            throw error
        }
        return id
    }

    fun cancel(taskId: Long): Boolean {
        val task = tasks[taskId] ?: return false
        task.cancel()
        return true
    }

    fun cancelOwner(owner: String): Int {
        val owned = tasks.values.filter { it.owner == owner }
        owned.forEach { it.cancel() }
        return owned.size
    }

    fun resize(workers: Int) {
        require(workers in 1..64) { "workers must be between 1 and 64" }
        val current = executor.corePoolSize
        if (workers > current) {
            executor.maximumPoolSize = workers
            executor.corePoolSize = workers
        } else if (workers < current) {
            executor.corePoolSize = workers
            executor.maximumPoolSize = workers
        }
    }

    fun snapshot(): KSLWorkSnapshot = KSLWorkSnapshot(
        workers = executor.corePoolSize,
        active = executor.activeCount,
        queued = executor.queue.size,
        tracked = tasks.size,
        completed = executor.completedTaskCount,
        rejected = rejected.get()
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tasks.values.toList().forEach { it.cancel() }
        executor.shutdownNow().forEach { queued ->
            (queued as? WorkTask)?.finish()
        }
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                // Running user code may ignore interruption; worker threads are daemon threads.
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
