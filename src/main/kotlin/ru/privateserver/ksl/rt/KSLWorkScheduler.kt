package ru.privateserver.ksl.rt

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Higher-ranked work runs first; equal priorities preserve FIFO order. */
enum class KSLWorkPriority(val rank: Int) { LOW(0), NORMAL(1), HIGH(2), CRITICAL(3) }

enum class KSLWorkState {
    SCHEDULED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;
    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

data class KSLWorkInfo(
    val id: Long, val owner: String, val priority: KSLWorkPriority, val state: KSLWorkState,
    val repeating: Boolean, val submittedAtMillis: Long, val nextRunAtMillis: Long,
    val startedAtMillis: Long?, val finishedAtMillis: Long?, val executions: Long,
    val skippedExecutions: Long, val failureType: String? = null, val failureMessage: String? = null
)

data class KSLWorkFailure(
    val taskId: Long, val owner: String, val priority: KSLWorkPriority,
    val failure: Throwable, val occurredAtMillis: Long
)

data class KSLWorkOwnerSnapshot(
    val tracked: Int, val queued: Int, val running: Int, val scheduled: Int,
    val executions: Long, val skippedExecutions: Long
)

data class KSLWorkSnapshot(
    val workers: Int, val active: Int, val queued: Int, val tracked: Int,
    val completed: Long, val rejected: Long,
    val scheduled: Int = 0, val running: Int = 0, val failed: Long = 0,
    val cancelled: Long = 0, val skipped: Long = 0, val capacity: Int = 0,
    val available: Int = 0, val largestPoolSize: Int = 0,
    val oldestQueuedMillis: Long = 0, val shutdown: Boolean = false,
    val perOwner: Map<String, KSLWorkOwnerSnapshot> = emptyMap()
)

data class KSLWorkResult(
    val id: Long, val owner: String, val state: KSLWorkState,
    val executions: Long, val durationMillis: Long,
    val failureType: String? = null, val failureMessage: String? = null
)

class KSLWorkHandle internal constructor(
    val id: Long, val owner: String, val completion: CompletableFuture<KSLWorkResult>,
    private val cancelAction: (Boolean) -> Boolean,
    private val infoAction: () -> KSLWorkInfo?
) {
    fun cancel(mayInterruptIfRunning: Boolean = true): Boolean = cancelAction(mayInterruptIfRunning)
    fun info(): KSLWorkInfo? = infoAction()
    fun await(timeout: Long, unit: TimeUnit): KSLWorkResult = completion.get(timeout, unit)
}

/**
 * Bounded script-owned worker pool with immediate, delayed and recurring work.
 * Timer callbacks only submit tasks; user code always runs on worker threads.
 * Bukkit API access belongs on the main server thread (for example through runSync()).
 */
private class MutableOwnerWorkSnapshot {
    var tracked = 0
    var queued = 0
    var running = 0
    var scheduled = 0
    var executions = 0L
    var skippedExecutions = 0L

    fun freeze() = KSLWorkOwnerSnapshot(
        tracked, queued, running, scheduled, executions, skippedExecutions
    )
}

class KSLWorkScheduler(
    workers: Int,
    private val queueCapacity: Int,
    private val perScriptLimit: Int = 64,
    private val failureHandler: ((KSLWorkFailure) -> Unit)? = null
) : AutoCloseable {
    private enum class Mode { ONCE, DELAYED, FIXED_RATE, FIXED_DELAY }

    private val ids = AtomicLong()
    private val order = AtomicLong()
    private val threadIds = AtomicInteger()
    private val closed = AtomicBoolean()
    private val rejected = AtomicLong()
    private val completed = AtomicLong()
    private val failed = AtomicLong()
    private val cancelled = AtomicLong()
    private val skipped = AtomicLong()
    private val outstanding = AtomicInteger()
    private val capacity = AtomicInteger(workers.coerceAtLeast(1) + queueCapacity.coerceAtLeast(1))
    private val ownerQuota = KSLPerOwnerQuota(perScriptLimit)
    private val records = ConcurrentHashMap<Long, Record>()
    // Owner-to-task index makes addon unload/cancelOwner proportional to that
    // addon's work instead of scanning every task owned by every addon.
    private val ownerTaskIds = ConcurrentHashMap<String, MutableSet<Long>>()
    private val history = ConcurrentLinkedDeque<KSLWorkInfo>()
    private val lifecycleLock = Any()

    private val executor = ThreadPoolExecutor(
        workers.coerceIn(1, 64), workers.coerceIn(1, 64), 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
        ThreadFactory { task ->
            Thread(task, "KSL-Worker-${threadIds.incrementAndGet()}").apply {
                isDaemon = true
                contextClassLoader = this@KSLWorkScheduler.javaClass.classLoader
            }
        }
    )
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { task ->
        Thread(task, "KSL-Worker-Timer").apply {
            isDaemon = true
            contextClassLoader = this@KSLWorkScheduler.javaClass.classLoader
        }
    }).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    private inner class Record(
        val id: Long, val owner: String, val priority: KSLWorkPriority,
        val mode: Mode, val periodNanos: Long, val action: () -> Unit
    ) {
        val lock = Any()
        val terminal = AtomicBoolean()
        val completion = CompletableFuture<KSLWorkResult>()
        @Volatile var state = KSLWorkState.SCHEDULED
        @Volatile var submittedAt = System.currentTimeMillis()
        @Volatile var nextRunAt = submittedAt
        @Volatile var startedAt: Long? = null
        @Volatile var finishedAt: Long? = null
        @Volatile var executions = 0L
        @Volatile var skippedExecutions = 0L
        @Volatile var failureType: String? = null
        @Volatile var failureMessage: String? = null
        @Volatile var stop = false
        // Guarded by lock.
        var inFlight = false
        var queuedAtNanos = 0L
        var runner: Thread? = null
        var workTask: WorkTask? = null
        var timerTask: ScheduledFuture<*>? = null
        val repeating get() = mode == Mode.FIXED_RATE || mode == Mode.FIXED_DELAY
        fun info() = KSLWorkInfo(
            id, owner, priority, state, repeating, submittedAt, nextRunAt,
            startedAt, finishedAt, executions, skippedExecutions, failureType, failureMessage
        )
    }

    private inner class WorkTask(val record: Record, private val sequence: Long) : Runnable, Comparable<WorkTask> {
        override fun compareTo(other: WorkTask): Int {
            val byPriority = other.record.priority.rank.compareTo(record.priority.rank)
            return if (byPriority != 0) byPriority else sequence.compareTo(other.sequence)
        }
        override fun run() = runRecord(record)
    }

    init {
        require(workers in 1..64) { "workers must be between 1 and 64" }
        require(queueCapacity in 1..100_000) { "queueCapacity must be between 1 and 100000" }
        require(perScriptLimit > 0) { "perScriptLimit must be positive" }
    }

    fun submit(owner: String, priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit): Long {
        val record = create(owner, priority, Mode.ONCE, 0L, action)
        enqueue(record)
        return record.id
    }

    fun submitHandle(owner: String, priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit): KSLWorkHandle {
        val record = create(owner, priority, Mode.ONCE, 0L, action)
        enqueue(record)
        return handle(record)
    }

    fun <T> submitFuture(
        owner: String, priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> T
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val id = submit(owner, priority) {
            try { future.complete(action()) }
            catch (error: Throwable) { future.completeExceptionally(error); throw error }
        }
        future.whenComplete { _, _ -> if (future.isCancelled) cancel(owner, id, true) }
        return future
    }

    fun schedule(
        owner: String, delay: Long, unit: TimeUnit = TimeUnit.MILLISECONDS,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit
    ): Long {
        require(delay >= 0L) { "delay must not be negative" }
        val record = create(owner, priority, Mode.DELAYED, 0L, action)
        armOnce(record, unit.toNanos(delay))
        return record.id
    }

    fun schedule(
        owner: String, delay: Duration, priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit
    ): Long {
        require(!delay.isNegative) { "delay must not be negative" }
        val nanos = try { delay.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        return schedule(owner, nanos, TimeUnit.NANOSECONDS, priority, action)
    }

    /** Delayed task with a cancellable handle and completion result. */
    fun scheduleHandle(
        owner: String, delay: Long, unit: TimeUnit = TimeUnit.MILLISECONDS,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit
    ): KSLWorkHandle {
        require(delay >= 0L) { "delay must not be negative" }
        val record = create(owner, priority, Mode.DELAYED, 0L, action)
        armOnce(record, unit.toNanos(delay))
        return handle(record)
    }

    fun scheduleHandle(
        owner: String, delay: Duration, priority: KSLWorkPriority = KSLWorkPriority.NORMAL,
        action: () -> Unit
    ): KSLWorkHandle {
        require(!delay.isNegative) { "delay must not be negative" }
        val nanos = try { delay.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        return scheduleHandle(owner, nanos, TimeUnit.NANOSECONDS, priority, action)
    }

    /** Fixed-rate task handle; overlapping ticks are counted as skipped. */
    fun scheduleAtFixedRateHandle(
        owner: String, initialDelay: Long, period: Long, unit: TimeUnit = TimeUnit.MILLISECONDS,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit
    ): KSLWorkHandle {
        require(initialDelay >= 0L) { "initialDelay must not be negative" }
        require(period > 0L) { "period must be positive" }
        val record = create(owner, priority, Mode.FIXED_RATE, unit.toNanos(period).coerceAtLeast(1L), action)
        armFixedRate(record, unit.toNanos(initialDelay))
        return handle(record)
    }

    /** Fixed-delay task handle; the next run is scheduled after the previous run completes. */
    fun scheduleWithFixedDelayHandle(
        owner: String, initialDelay: Long, delay: Long, unit: TimeUnit = TimeUnit.MILLISECONDS,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit
    ): KSLWorkHandle {
        require(initialDelay >= 0L) { "initialDelay must not be negative" }
        require(delay > 0L) { "delay must be positive" }
        val record = create(owner, priority, Mode.FIXED_DELAY, unit.toNanos(delay).coerceAtLeast(1L), action)
        armOnce(record, unit.toNanos(initialDelay))
        return handle(record)
    }

    /** Fixed-rate ticks are skipped when the previous execution is still active. */
    fun scheduleAtFixedRate(
        owner: String, initialDelay: Long, period: Long, unit: TimeUnit = TimeUnit.MILLISECONDS,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit
    ): Long {
        require(initialDelay >= 0L) { "initialDelay must not be negative" }
        require(period > 0L) { "period must be positive" }
        val record = create(owner, priority, Mode.FIXED_RATE, unit.toNanos(period).coerceAtLeast(1L), action)
        armFixedRate(record, unit.toNanos(initialDelay))
        return record.id
    }

    /** Fixed-delay tasks never overlap; delay is measured from completion of the previous run. */
    fun scheduleWithFixedDelay(
        owner: String, initialDelay: Long, delay: Long, unit: TimeUnit = TimeUnit.MILLISECONDS,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL, action: () -> Unit
    ): Long {
        require(initialDelay >= 0L) { "initialDelay must not be negative" }
        require(delay > 0L) { "delay must be positive" }
        val record = create(owner, priority, Mode.FIXED_DELAY, unit.toNanos(delay).coerceAtLeast(1L), action)
        armOnce(record, unit.toNanos(initialDelay))
        return record.id
    }

    private fun create(owner: String, priority: KSLWorkPriority, mode: Mode, period: Long, action: () -> Unit): Record {
        require(owner.isNotBlank()) { "owner must not be blank" }
        synchronized(lifecycleLock) {
            check(!closed.get()) { "KSL work scheduler is closed" }
            if (!ownerQuota.acquire(owner)) {
                rejected.incrementAndGet()
                throw RejectedExecutionException("Script '$owner' exceeded worker task limit ($perScriptLimit)")
            }
            if (!reserve()) {
                ownerQuota.release(owner)
                rejected.incrementAndGet()
                throw RejectedExecutionException("KSL worker capacity is full")
            }
            return Record(ids.incrementAndGet(), owner, priority, mode, period, action).also { record ->
                records[record.id] = record
                ownerTaskIds.compute(owner) { _, current ->
                    (current ?: ConcurrentHashMap.newKeySet<Long>()).also { it.add(record.id) }
                }
            }
        }
    }

    private fun reserve(): Boolean {
        while (true) {
            val current = outstanding.get()
            if (current >= capacity.get()) return false
            if (outstanding.compareAndSet(current, current + 1)) return true
        }
    }

    private fun enqueue(record: Record) {
        var terminal: KSLWorkResult? = null
        var error: Throwable? = null
        synchronized(record.lock) {
            if (record.terminal.get() || record.stop || closed.get()) return
            if (record.inFlight) {
                record.skippedExecutions++
                skipped.incrementAndGet()
                return
            }
            record.inFlight = true
            record.state = KSLWorkState.QUEUED
            record.queuedAtNanos = System.nanoTime()
            record.workTask = WorkTask(record, order.incrementAndGet())
            try { executor.execute(record.workTask) }
            catch (failure: Throwable) {
                record.workTask = null
                record.inFlight = false
                rejected.incrementAndGet()
                error = failure
                terminal = finishLocked(record, KSLWorkState.FAILED, failure)
            }
        }
        if (terminal != null) complete(record, terminal!!, error)
    }

    private fun runRecord(record: Record) {
        var cancelledBeforeStart: KSLWorkResult? = null
        synchronized(record.lock) {
            if (record.terminal.get()) return
            if (record.stop) {
                record.workTask = null
                record.inFlight = false
                cancelledBeforeStart = finishLocked(record, KSLWorkState.CANCELLED, null)
            } else {
                record.workTask = null
                record.runner = Thread.currentThread()
                record.state = KSLWorkState.RUNNING
                record.startedAt = System.currentTimeMillis()
                record.executions++
            }
        }
        if (cancelledBeforeStart != null) {
            complete(record, cancelledBeforeStart!!, null)
            return
        }

        var error: Throwable? = null
        try { record.action() } catch (failure: Throwable) { error = failure }
        finally {
            var terminal: KSLWorkResult? = null
            var repeatDelay = false
            synchronized(record.lock) {
                record.runner = null
                record.inFlight = false
                when {
                    record.stop -> terminal = finishLocked(record, KSLWorkState.CANCELLED, null)
                    error != null -> terminal = finishLocked(record, KSLWorkState.FAILED, error)
                    else -> {
                        completed.incrementAndGet()
                        if (!record.repeating) {
                            terminal = finishLocked(record, KSLWorkState.SUCCEEDED, null)
                        } else if (closed.get()) {
                            // A graceful shutdown may become visible before its cancellation
                            // pass reaches this record. Never report a repeating task as
                            // successful merely because its active invocation finished in
                            // that small window.
                            terminal = finishLocked(record, KSLWorkState.CANCELLED, null)
                        } else {
                            record.state = KSLWorkState.SCHEDULED
                            record.nextRunAt = System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(record.periodNanos)
                            repeatDelay = record.mode == Mode.FIXED_DELAY
                        }
                    }
                }
            }
            if (terminal != null) complete(record, terminal!!, error)
            if (repeatDelay) armOnce(record, record.periodNanos)
        }
    }

    private fun armOnce(record: Record, nanos: Long) {
        var terminal: KSLWorkResult? = null
        var error: Throwable? = null
        synchronized(record.lock) {
            if (record.terminal.get() || record.stop || closed.get()) return
            record.state = KSLWorkState.SCHEDULED
            record.nextRunAt = System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(nanos)
            try { record.timerTask = timer.schedule({ enqueue(record) }, nanos, TimeUnit.NANOSECONDS) }
            catch (failure: Throwable) {
                error = failure
                terminal = finishLocked(record, KSLWorkState.FAILED, failure)
            }
        }
        if (terminal != null) complete(record, terminal!!, error)
    }

    private fun armFixedRate(record: Record, initialNanos: Long) {
        var terminal: KSLWorkResult? = null
        var error: Throwable? = null
        synchronized(record.lock) {
            if (record.terminal.get() || record.stop || closed.get()) return
            record.state = KSLWorkState.SCHEDULED
            record.nextRunAt = System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(initialNanos)
            try {
                record.timerTask = timer.scheduleAtFixedRate(
                    { enqueue(record) }, initialNanos, record.periodNanos, TimeUnit.NANOSECONDS
                )
            } catch (failure: Throwable) {
                error = failure
                terminal = finishLocked(record, KSLWorkState.FAILED, failure)
            }
        }
        if (terminal != null) complete(record, terminal!!, error)
    }

    private fun finishLocked(record: Record, state: KSLWorkState, error: Throwable?): KSLWorkResult? {
        if (!record.terminal.compareAndSet(false, true)) return null
        record.state = state
        record.finishedAt = System.currentTimeMillis()
        record.timerTask?.cancel(false)
        record.timerTask = null
        if (error != null) {
            record.failureType = error.javaClass.simpleName.ifBlank { error.javaClass.name }
            record.failureMessage = error.message?.take(500)
        }
        records.remove(record.id, record)
        ownerTaskIds.computeIfPresent(record.owner) { _, ids ->
            ids.remove(record.id)
            ids.takeIf { it.isNotEmpty() }
        }
        ownerQuota.release(record.owner)
        outstanding.decrementAndGet()
        if (state == KSLWorkState.FAILED) failed.incrementAndGet()
        if (state == KSLWorkState.CANCELLED) cancelled.incrementAndGet()
        remember(record.info())
        return KSLWorkResult(
            record.id, record.owner, state, record.executions,
            ((record.finishedAt ?: 0L) - record.submittedAt).coerceAtLeast(0L),
            record.failureType, record.failureMessage
        )
    }

    private fun complete(record: Record, result: KSLWorkResult, error: Throwable?) {
        if (result.state == KSLWorkState.FAILED) {
            val cause = error ?: IllegalStateException(result.failureMessage ?: "Worker task failed")
            record.completion.completeExceptionally(cause)
            runCatching {
                failureHandler?.invoke(KSLWorkFailure(record.id, record.owner, record.priority, cause, System.currentTimeMillis()))
            }
        } else {
            // Cancellation is a task outcome, not a missing result: await() and
            // completion callbacks can inspect KSLWorkState.CANCELLED.
            record.completion.complete(result)
        }
    }

    private fun remember(info: KSLWorkInfo) {
        history.addFirst(info)
        while (history.size > HISTORY_LIMIT) history.pollLast()
    }

    private fun handle(record: Record) = KSLWorkHandle(
        record.id, record.owner, record.completion,
        { interrupt -> cancelRecord(record, interrupt) }, { taskInfo(record.id) }
    )

    fun cancel(taskId: Long, mayInterruptIfRunning: Boolean = true): Boolean =
        records[taskId]?.let { cancelRecord(it, mayInterruptIfRunning) } ?: false

    fun cancel(owner: String, taskId: Long, mayInterruptIfRunning: Boolean = true): Boolean {
        val record = records[taskId] ?: return false
        if (record.owner != owner) return false
        return cancelRecord(record, mayInterruptIfRunning)
    }

    private fun cancelRecord(record: Record, interrupt: Boolean): Boolean {
        var terminal: KSLWorkResult? = null
        synchronized(record.lock) {
            if (record.terminal.get()) return false
            record.stop = true
            record.timerTask?.cancel(false)
            record.timerTask = null
            val queued = record.workTask
            if (queued != null && executor.remove(queued)) {
                record.workTask = null
                record.inFlight = false
                terminal = finishLocked(record, KSLWorkState.CANCELLED, null)
            } else {
                val runner = record.runner
                if (runner != null && interrupt) runner.interrupt()
                if (runner == null && !record.inFlight) terminal = finishLocked(record, KSLWorkState.CANCELLED, null)
            }
        }
        if (terminal != null) complete(record, terminal!!, null)
        return true
    }

    fun cancelOwner(owner: String): Int {
        val ownedIds = ownerTaskIds[owner]?.toList() ?: return 0
        var count = 0
        ownedIds.forEach { id ->
            records[id]?.let { if (cancelRecord(it, true)) count++ }
        }
        return count
    }

    fun resize(workers: Int) {
        require(workers in 1..64) { "workers must be between 1 and 64" }
        synchronized(lifecycleLock) {
            check(!closed.get()) { "KSL work scheduler is closed" }
            val current = executor.corePoolSize
            if (workers > current) {
                executor.maximumPoolSize = workers
                executor.corePoolSize = workers
            } else if (workers < current) {
                executor.corePoolSize = workers
                executor.maximumPoolSize = workers
            }
            capacity.set(workers + queueCapacity)
        }
    }

    fun taskInfo(taskId: Long): KSLWorkInfo? = records[taskId]?.info() ?: history.firstOrNull { it.id == taskId }

    fun taskInfos(owner: String? = null, limit: Int = HISTORY_LIMIT): List<KSLWorkInfo> {
        require(limit in 1..HISTORY_LIMIT) { "limit must be between 1 and $HISTORY_LIMIT" }
        val active = if (owner == null) {
            records.values.map { it.info() }
        } else {
            ownerTaskIds[owner]?.mapNotNull { id -> records[id]?.info() } ?: emptyList()
        }
        val recent = history.filter { owner == null || it.owner == owner }.take(limit)
        return (active + recent).distinctBy { it.id }.sortedByDescending { it.id }.take(limit)
    }

    fun snapshot(): KSLWorkSnapshot {
        // One weakly-consistent pass avoids allocating an intermediate task list,
        // groupBy buckets, and multiple full scans while diagnostics poll the scheduler.
        val byOwner = HashMap<String, MutableOwnerWorkSnapshot>()
        var queued = 0
        var scheduled = 0
        var running = 0
        var oldestQueuedAt = Long.MAX_VALUE

        records.values.forEach { record ->
            val owner = byOwner.getOrPut(record.owner) { MutableOwnerWorkSnapshot() }
            owner.tracked++
            owner.executions += record.executions
            owner.skippedExecutions += record.skippedExecutions
            when (record.state) {
                KSLWorkState.QUEUED -> {
                    owner.queued++
                    queued++
                    if (record.queuedAtNanos > 0L) {
                        oldestQueuedAt = minOf(oldestQueuedAt, record.queuedAtNanos)
                    }
                }
                KSLWorkState.RUNNING -> {
                    owner.running++
                    running++
                }
                KSLWorkState.SCHEDULED -> {
                    owner.scheduled++
                    scheduled++
                }
                else -> Unit
            }
        }

        val byOwnerSnapshot = byOwner.mapValues { (_, value) -> value.freeze() }
        val oldestQueuedMillis = if (oldestQueuedAt == Long.MAX_VALUE) {
            0L
        } else {
            TimeUnit.NANOSECONDS.toMillis(
                (System.nanoTime() - oldestQueuedAt).coerceAtLeast(0L)
            )
        }
        val max = capacity.get()
        val used = outstanding.get()
        return KSLWorkSnapshot(
            executor.corePoolSize, executor.activeCount,
            queued, byOwnerSnapshot.values.sumOf { it.tracked },
            completed.get(), rejected.get(),
            scheduled, running,
            failed.get(), cancelled.get(), skipped.get(), max,
            (max - used).coerceAtLeast(0), executor.largestPoolSize,
            oldestQueuedMillis, closed.get(), byOwnerSnapshot
        )
    }

    /** Stop accepting new work; queued/running one-shots finish, delayed/repeating work is cancelled. */
    fun shutdownGracefully(timeout: Long, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        require(timeout >= 0L) { "timeout must not be negative" }
        synchronized(lifecycleLock) {
            if (closed.get()) return executor.isTerminated
            closed.set(true)
        }
        timer.shutdownNow()
        records.values.filter { it.repeating || it.state == KSLWorkState.SCHEDULED }.forEach { cancelRecord(it, false) }
        executor.shutdown()
        return try {
            if (executor.awaitTermination(timeout, unit)) true else {
                records.values.toList().forEach { cancelRecord(it, true) }
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS)
                false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            records.values.toList().forEach { cancelRecord(it, true) }
            executor.shutdownNow()
            false
        }
    }

    /** Snapshot active records without iterating a weakly-consistent Kotlin view. */
    private fun activeRecordsSnapshot(): List<Record> = records.values.stream().toList()

    /** Immediate bounded shutdown. Running user code is interrupted where possible. */
    override fun close() {
        synchronized(lifecycleLock) { closed.set(true) }
        timer.shutdownNow()
        activeRecordsSnapshot().forEach { cancelRecord(it, true) }
        executor.shutdownNow().forEach { queued -> (queued as? WorkTask)?.let { cancelRecord(it.record, false) } }
        try { executor.awaitTermination(2, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    companion object { const val HISTORY_LIMIT = 256 }
}

/** Atomic per-owner quota; compute() keeps acquire/release linearizable under contention. */
internal class KSLPerOwnerQuota(private val limit: Int) {
    private val counts = ConcurrentHashMap<String, Int>()
    init { require(limit > 0) { "limit must be positive" } }
    fun acquire(owner: String): Boolean {
        var accepted = false
        counts.compute(owner) { _, current ->
            val used = current ?: 0
            if (used >= limit) used else { accepted = true; used + 1 }
        }
        return accepted
    }
    fun release(owner: String) {
        counts.computeIfPresent(owner) { _, current -> if (current <= 1) null else current - 1 }
    }
}
