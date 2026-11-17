package ru.privateserver.ksl.kslshu

import ru.privateserver.ksl.rt.KSLWorkHandle
import ru.privateserver.ksl.rt.KSLWorkPriority
import ru.privateserver.ksl.rt.KSLWorkScheduler
import ru.privateserver.ksl.rt.KSLWorkSnapshot
import ru.privateserver.ksl.rt.KSLRuntimeScope
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class KSLTaskRepeatMode { FIXED_RATE, FIXED_DELAY }
data class KSLTaskContext(val owner: String, val execution: Long, val submittedAtMillis: Long)
enum class KSLTaskState { SUCCEEDED, FAILED, CANCELLED }
data class KSLTaskResult(
    val id: Long, val owner: String, val state: KSLTaskState, val executions: Long,
    val durationMillis: Long, val failureType: String? = null, val failureMessage: String? = null
)

/** Portable task definition. Actions run on the scheduler execution thread, not necessarily Bukkit's main thread. */
data class KSLTaskSpec(
    val owner: String,
    val delay: Duration = Duration.ZERO,
    val interval: Duration? = null,
    val repeatMode: KSLTaskRepeatMode = KSLTaskRepeatMode.FIXED_DELAY,
    val priority: KSLWorkPriority = KSLWorkPriority.NORMAL,
    val action: (KSLTaskContext) -> Unit
) {
    init {
        require(owner.isNotBlank()) { "Task owner must not be blank" }
        require(!delay.isNegative) { "Task delay must not be negative" }
        require(interval == null || (!interval.isNegative && !interval.isZero)) {
            "Task interval must be positive when repeating"
        }
    }
    internal fun delayNanos(): Long = delay.safeNanos()
    internal fun intervalNanos(): Long? = interval?.safeNanos()
    private fun Duration.safeNanos(): Long = try { toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
}

class KSLTaskHandle(
    val id: Long, val owner: String, val completion: CompletableFuture<KSLTaskResult>,
    private val cancelAction: (Boolean) -> Boolean
) {
    fun cancel(mayInterruptIfRunning: Boolean = true): Boolean = cancelAction(mayInterruptIfRunning)
}

/** SPI for first-party and addon-provided scheduler engines. */
interface KSLTaskScheduler : AutoCloseable {
    fun schedule(spec: KSLTaskSpec): KSLTaskHandle
    fun cancel(owner: String, taskId: Long, mayInterruptIfRunning: Boolean = true): Boolean
    fun cancelOwner(owner: String): Int
    fun snapshot(): KSLWorkSnapshot
}

data class KSLTaskSchedulerEnvironment(val officialWorkers: KSLWorkScheduler)
fun interface KSLTaskSchedulerFactory {
    fun create(environment: KSLTaskSchedulerEnvironment): KSLTaskScheduler
}

/** Thread-safe factory registry. IDs are stable, lowercase identifiers. */
class KSLTaskSchedulerRegistry(private val environment: KSLTaskSchedulerEnvironment) {
    private val factories = linkedMapOf<String, KSLTaskSchedulerFactory>()

    @Synchronized
    fun register(id: String, factory: KSLTaskSchedulerFactory): AutoCloseable {
        val key = normalize(id)
        synchronized(this) {
            require(key !in factories) { "A KSL scheduler factory named '$key' is already registered" }
            factories[key] = factory
        }
        return AutoCloseable {
            synchronized(this) {
                if (factories[key] === factory) factories.remove(key)
            }
        }
    }

    @Synchronized
    fun available(): Set<String> = factories.keys.toSet()

    @Synchronized
    fun create(id: String): KSLTaskScheduler {
        val key = normalize(id)
        val factory = factories[key] ?: throw IllegalArgumentException(
            "Unknown KSL scheduler '$key'. Available: ${factories.keys.sorted().joinToString().ifEmpty { "none" }}"
        )
        return factory.create(environment)
    }

    private fun normalize(id: String): String {
        val key = id.trim().lowercase()
        require(key.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "Scheduler ID must use 1-64 lowercase letters, digits, '.', '_' or '-'"
        }
        return key
    }

    companion object {
        fun withOfficial(environment: KSLTaskSchedulerEnvironment) =
            KSLTaskSchedulerRegistry(environment).apply {
                register("ksl") { env -> KSLOfficialTaskScheduler(env.officialWorkers) }
            }
    }
}

/** Official adapter over KSL's bounded priority worker pool. */
class KSLOfficialTaskScheduler(private val workers: KSLWorkScheduler) : KSLTaskScheduler {
    override fun schedule(spec: KSLTaskSpec): KSLTaskHandle {
        val executions = AtomicLong()
        val submittedAt = System.currentTimeMillis()
        val action = { spec.action(KSLTaskContext(spec.owner, executions.incrementAndGet(), submittedAt)) }
        val delay = spec.delayNanos()
        val interval = spec.intervalNanos()
        val work = when {
            interval == null && delay == 0L ->
                workers.submitHandle(spec.owner, spec.priority, action)
            interval == null ->
                workers.scheduleHandle(spec.owner, delay, TimeUnit.NANOSECONDS, spec.priority, action)
            spec.repeatMode == KSLTaskRepeatMode.FIXED_RATE ->
                workers.scheduleAtFixedRateHandle(spec.owner, delay, interval, TimeUnit.NANOSECONDS, spec.priority, action)
            else ->
                workers.scheduleWithFixedDelayHandle(spec.owner, delay, interval, TimeUnit.NANOSECONDS, spec.priority, action)
        }
        val completion = CompletableFuture<KSLTaskResult>()
        work.completion.whenComplete { value, error ->
            if (error != null) completion.completeExceptionally(error)
            else completion.complete(
                KSLTaskResult(value.id, value.owner, KSLTaskState.valueOf(value.state.name),
                    value.executions, value.durationMillis, value.failureType, value.failureMessage)
            )
        }
        return KSLTaskHandle(work.id, work.owner, completion) { interrupt -> work.cancel(interrupt) }
    }

    override fun cancel(owner: String, taskId: Long, mayInterruptIfRunning: Boolean) =
        workers.cancel(owner, taskId, mayInterruptIfRunning)
    override fun cancelOwner(owner: String): Int = workers.cancelOwner(owner)
    override fun snapshot(): KSLWorkSnapshot = workers.snapshot()
    /** The plugin owns the shared official pool's lifecycle. */
    override fun close() = Unit
}

/** Script-bound facade: task ownership is always the current script ID. */
class KSLTaskScope internal constructor(
    private val scheduler: KSLTaskScheduler,
    private val owner: String,
    private val generationScope: KSLRuntimeScope?
) {
    // Preserve the original constructor for existing addon integrations.
    internal constructor(scheduler: KSLTaskScheduler, owner: String) : this(scheduler, owner, null)

    private fun ensureActive() {
        check(generationScope?.isClosed != true) {
            "Task scope '$owner' belongs to an unloaded runtime generation"
        }
    }

    private fun own(handle: KSLTaskHandle): KSLTaskHandle {
        val scope = generationScope ?: return handle
        val registration = try {
            scope.onClose { handle.cancel(true) }
        } catch (error: IllegalStateException) {
            // onClose runs the cleanup immediately when closure wins the race.
            handle.cancel(true)
            throw error
        }
        // One-shot work releases its ownership as soon as it completes; repeating
        // work remains owned until explicitly cancelled or the generation closes.
        handle.completion.whenComplete { _, _ -> registration.close() }
        return handle
    }

    private fun submit(spec: KSLTaskSpec): KSLTaskHandle {
        ensureActive()
        val guarded = spec.copy(
            owner = owner,
            action = { context ->
                ensureActive()
                spec.action(context)
            }
        )
        return own(scheduler.schedule(guarded))
    }

    /**
     * Schedules a complete task specification while enforcing this scope's owner.
     * A caller cannot use a spec to submit work under another script/addon identity.
     */
    fun schedule(spec: KSLTaskSpec): KSLTaskHandle = submit(spec)

    fun schedule(
        delay: Duration = Duration.ZERO,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL,
        action: (KSLTaskContext) -> Unit
    ): KSLTaskHandle = submit(KSLTaskSpec(owner, delay, priority = priority, action = action))

    fun repeat(
        interval: Duration,
        initialDelay: Duration = Duration.ZERO,
        mode: KSLTaskRepeatMode = KSLTaskRepeatMode.FIXED_DELAY,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL,
        action: (KSLTaskContext) -> Unit
    ): KSLTaskHandle = submit(KSLTaskSpec(owner, initialDelay, interval, mode, priority, action))

    fun cancel(taskId: Long, mayInterruptIfRunning: Boolean = true): Boolean {
        ensureActive()
        return scheduler.cancel(owner, taskId, mayInterruptIfRunning)
    }

    fun cancelAll(): Int {
        ensureActive()
        return scheduler.cancelOwner(owner)
    }

    fun snapshot(): KSLWorkSnapshot {
        ensureActive()
        return scheduler.snapshot()
    }
}
