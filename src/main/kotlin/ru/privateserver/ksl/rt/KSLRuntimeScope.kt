package ru.privateserver.ksl.rt

import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor

/**
 * Owns runtime resources with an explicit lifetime.
 * Resources close in reverse registration order; closing is idempotent.
 */
class KSLRuntimeScope(val name: String) : AutoCloseable {
    private class Cleanup(@Volatile var action: (() -> Unit)?)

    private val lock = Any()
    private val cleanups = ArrayList<Cleanup>()
    private val profilers = LinkedHashMap<String, KSLProfiler>()
    private val closeFinished = CountDownLatch(1)

    @Volatile private var closed = false
    private var closingThread: Thread? = null

    init {
        require(name.isNotBlank()) { "Runtime scope name must not be blank" }
        require(name.length <= KSLProfiler.MAX_NAME_LENGTH) {
            "Runtime scope name must not exceed ${KSLProfiler.MAX_NAME_LENGTH} characters"
        }
    }

    val isClosed: Boolean get() = closed
    val ownedResourceCount: Int get() = synchronized(lock) { cleanups.size }
    val profilerNames: Set<String> get() = synchronized(lock) { profilers.keys.toSet() }

    /**
     * Creates a nested scope whose resources are released with this scope.
     *
     * Closing the child manually detaches it from the parent, so the parent does
     * not retain a completed child or its captured resources until its own close.
     * Nested scopes may be composed to mirror script, addon and subsystem lifetimes.
     */
    fun childScope(name: String): KSLRuntimeScope = synchronized(lock) {
        check(!closed) { "Runtime scope '${this.name}' is already closed" }
        val child = KSLRuntimeScope(name)
        val parentRegistration = registerCleanup { child.close() }
        child.onClose { parentRegistration.close() }
        child
    }

    /** Transfers lifecycle responsibility to this scope and returns the resource. */
    fun <T : AutoCloseable> own(resource: T): T {
        registerCleanup { resource.close() }
        return resource
    }

    /**
     * Registers cleanup code. Closing the returned handle unregisters the action
     * without running it, allowing early release of captured objects.
     */
    fun onClose(action: () -> Unit): AutoCloseable = registerCleanup(action)

    /**
     * Attaches a scheduler task to this scope's lifetime.
     *
     * The task is cancelled (and its running worker interrupted where possible)
     * when the scope closes. Once the task reaches any terminal state, its
     * ownership registration is removed automatically.
     */
    fun own(handle: KSLWorkHandle): KSLWorkHandle {
        val registration = onClose { handle.cancel(true) }
        handle.completion.whenComplete { _, _ -> registration.close() }
        return handle
    }

    /**
     * Submits work owned by this runtime scope.
     *
     * The future is cancelled and the worker is interrupted when the scope closes.
     * ThreadPoolExecutor queues are also cleaned up so a reload does not leave stale
     * work waiting behind the replacement script.
     */
    fun <T> submit(executor: Executor, operation: () -> T): CompletableFuture<T> {
        val completion = CompletableFuture<T>()
        lateinit var task: FutureTask<Unit>

        fun removeTask() {
            if (executor is ThreadPoolExecutor) executor.remove(task)
        }

        lateinit var ownership: AutoCloseable
        task = FutureTask {
            if (!isClosed && !completion.isCancelled) {
                try {
                    val value = operation()
                    if (isClosed || completion.isCancelled) {
                        completion.cancel(false)
                    } else {
                        // Release scope bookkeeping before exposing completion to callers.
                        ownership.close()
                        completion.complete(value)
                    }
                } catch (error: Throwable) {
                    ownership.close()
                    if (!completion.isDone) completion.completeExceptionally(error)
                }
            }
            Unit
        }

        ownership = try {
            onClose {
                removeTask()
                task.cancel(true)
                completion.cancel(true)
            }
        } catch (error: IllegalStateException) {
            if (!completion.isDone) completion.completeExceptionally(error)
            return completion
        }

        completion.whenComplete { _, _ ->
            if (completion.isCancelled) {
                removeTask()
                task.cancel(true)
            }
            ownership.close()
        }

        try {
            executor.execute(task)
            // Scope closure/cancellation can race with the hand-off to the executor.
            // Re-check after enqueueing so a cancelled task cannot remain in the queue.
            if (completion.isCancelled || isClosed) {
                removeTask()
                task.cancel(true)
            }
        } catch (error: Throwable) {
            ownership.close()
            completion.completeExceptionally(error)
        }
        return completion
    }

    private fun registerCleanup(action: () -> Unit): AutoCloseable {
        val cleanup = Cleanup(action)
        val runImmediately = synchronized(lock) {
            if (closed) true else {
                check(cleanups.size < MAX_OWNED_RESOURCES) {
                    "Runtime scope '$name' reached its resource limit ($MAX_OWNED_RESOURCES)"
                }
                cleanups.add(cleanup)
                false
            }
        }
        if (runImmediately) {
            action()
            throw IllegalStateException("Runtime scope '$name' is already closed")
        }
        return AutoCloseable {
            synchronized(lock) {
                cleanups.remove(cleanup)
                cleanup.action = null
            }
        }
    }

    fun <K : Any, V : Any> cache(
        maxSize: Int,
        expireAfter: Duration = Duration.ZERO,
        expireAfterAccess: Duration = Duration.ZERO
    ): KSLCache<K, V> {
        val cache = KSLRT.cache<K, V>(maxSize, expireAfter, expireAfterAccess)
        registerCleanup { cache.clear() }
        return cache
    }

    /** Alias for [cache], emphasizing the eviction policy. */
    fun <K : Any, V : Any> lru(
        maxSize: Int,
        expireAfter: Duration = Duration.ZERO,
        expireAfterAccess: Duration = Duration.ZERO
    ): KSLCache<K, V> = cache(maxSize, expireAfter, expireAfterAccess)

    fun profiler(name: String): KSLProfiler = synchronized(lock) {
        check(!closed) { "Runtime scope '${this.name}' is already closed" }
        require(name.isNotBlank()) { "Profiler name must not be blank" }
        require(name.length <= KSLProfiler.MAX_NAME_LENGTH) {
            "Profiler name must not exceed ${KSLProfiler.MAX_NAME_LENGTH} characters"
        }
        profilers[name]?.let { return@synchronized it }
        check(profilers.size < MAX_PROFILERS_PER_SCOPE) {
            "Runtime scope '$name' reached its profiler limit ($MAX_PROFILERS_PER_SCOPE)"
        }
        KSLProfiler(name).also { profilers[name] = it }
    }

    /** Alias for [profiler]. */
    fun profile(name: String): KSLProfiler = profiler(name)

    inline fun <T> measure(name: String, block: () -> T): T = profiler(name).measure(block)
    inline fun <T> timed(name: String, block: () -> T): T = measure(name, block)

    fun profilerOrNull(name: String): KSLProfiler? = synchronized(lock) { profilers[name] }
    fun removeProfiler(name: String): Boolean = synchronized(lock) { profilers.remove(name) != null }

    fun clearProfilers(): Int = synchronized(lock) {
        val removed = profilers.size
        profilers.clear()
        removed
    }

    fun profileSnapshots(): List<KSLProfileSnapshot> =
        synchronized(lock) { profilers.values.map { it.snapshot() } }

    /**
     * Closes resources once. Concurrent callers wait for the first close operation
     * to finish; a cleanup callback may safely call close() again on its own thread.
     */
    override fun close() {
        val actions = synchronized(lock) {
            if (closed) {
                if (closingThread === Thread.currentThread()) return
                null
            } else {
                closed = true
                closingThread = Thread.currentThread()
                profilers.clear()
                cleanups.asReversed().mapNotNull { it.action }.also {
                    cleanups.forEach { cleanup -> cleanup.action = null }
                    cleanups.clear()
                }
            }
        }

        if (actions == null) {
            awaitCloseCompletion()
            return
        }

        var failure: Throwable? = null
        try {
            actions.forEach { action ->
                try {
                    action()
                } catch (error: Throwable) {
                    if (failure == null) {
                        failure = IllegalStateException("One or more resources failed to close in runtime scope '$name'", error)
                    } else {
                        failure!!.addSuppressed(error)
                    }
                }
            }
        } finally {
            synchronized(lock) { closingThread = null }
            closeFinished.countDown()
        }
        failure?.let { throw it }
    }

    /**
     * Waits without abandoning cleanup if interrupted, then restores the caller's
     * interrupt flag so shutdown coordination remains reliable and interruption-safe.
     */
    private fun awaitCloseCompletion() {
        var interrupted = false
        while (true) {
            try {
                closeFinished.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        const val MAX_OWNED_RESOURCES: Int = 4096
        const val MAX_PROFILERS_PER_SCOPE: Int = 128
    }
}
