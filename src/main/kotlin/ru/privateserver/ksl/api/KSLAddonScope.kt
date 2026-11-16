package ru.privateserver.ksl.api

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.privateserver.ksl.KSLError
import ru.privateserver.ksl.KotlinScriptLoaderPlugin
import ru.privateserver.ksl.kslshu.KSLTaskContext
import ru.privateserver.ksl.kslshu.KSLTaskHandle
import ru.privateserver.ksl.kslshu.KSLTaskRepeatMode
import ru.privateserver.ksl.kslshu.KSLTaskScope
import ru.privateserver.ksl.rt.KSLWorkPriority
import java.time.Duration

/**
 * Explicit owner for resources created by a KSL addon.
 *
 * Create one in [KSLAddon.onLoad] and close it in [KSLAddon.onUnload].
 * Resources are released in reverse registration order. This scope does not
 * provide JVM/process isolation and does not make Bukkit APIs thread-safe.
 */
class KSLAddonScope internal constructor(
    private val owner: Plugin,
    private val subscribeAction: (String, (Any?) -> Unit) -> AutoCloseable,
    private val taskScope: KSLTaskScope? = null
) : AutoCloseable {

    private val lock = Any()
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false

    /** True after [close] has started. */
    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    /** Number of resources still owned by this scope. */
    val resourceCount: Int
        get() = synchronized(lock) { resources.size }

    /**
     * Adds an external resource to this scope.
     *
     * If the scope is already closed, the resource is closed immediately and
     * an [IllegalStateException] is thrown.
     */
    fun <T : AutoCloseable> own(resource: T): T {
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                resources.add(resource)
                true
            }
        }
        if (!accepted) {
            runCatching { resource.close() }
            throw IllegalStateException("KSL addon scope is already closed")
        }
        return resource
    }

    /** Removes and closes one previously owned resource. */
    fun release(resource: AutoCloseable): Boolean {
        val removed = synchronized(lock) {
            val index = resources.indexOfLast { it === resource }
            if (index < 0) null else resources.removeAt(index)
        } ?: return false
        removed.close()
        return true
    }

    /**
     * Schedules delayed work on the official KSL worker engine.
     * The returned handle is owned by this scope and cancelled when it closes.
     */
    fun schedule(
        delay: Duration = Duration.ZERO,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL,
        action: (KSLTaskContext) -> Unit
    ): KSLTaskHandle {
        val scheduler = taskScope ?: error("KSL scheduler is unavailable for this addon scope")
        return ownTask(scheduler.schedule(delay, priority, action))
    }

    /** Schedules recurring work; FIXED_DELAY waits after completion, FIXED_RATE follows the target cadence. */
    fun repeat(
        interval: Duration,
        initialDelay: Duration = Duration.ZERO,
        mode: KSLTaskRepeatMode = KSLTaskRepeatMode.FIXED_DELAY,
        priority: KSLWorkPriority = KSLWorkPriority.NORMAL,
        action: (KSLTaskContext) -> Unit
    ): KSLTaskHandle {
        val scheduler = taskScope ?: error("KSL scheduler is unavailable for this addon scope")
        return ownTask(scheduler.repeat(interval, initialDelay, mode, priority, action))
    }

    /** Adopts an existing task handle and cancels it when this scope closes. */
    fun ownTask(handle: KSLTaskHandle): KSLTaskHandle {
        val resource = AutoCloseable { handle.cancel(true) }
        own(resource)
        // A completed one-shot task must not remain retained by the scope.
        handle.completion.whenComplete { _, _ -> runCatching { release(resource) } }
        return handle
    }

    /** Subscribes to a KSL event-bus topic and owns the returned subscription. */
    fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable =
        own(subscribeAction(topic, handler))

    /**
     * Registers a Bukkit listener and automatically unregisters it on [close].
     * Registration must happen on the server thread.
     */
    fun <T : Listener> registerEvents(listener: T): T {
        Bukkit.getPluginManager().registerEvents(listener, owner)
        own(AutoCloseable { HandlerList.unregisterAll(listener) })
        return listener
    }

    /**
     * Registers one typed Bukkit event handler.
     * Registration must happen on the server thread.
     */
    fun <T : Event> listen(
        eventType: Class<T>,
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        handler: (T) -> Unit
    ): AutoCloseable {
        val listener = object : Listener {}
        val executor = EventExecutor { _, event ->
            if (eventType.isInstance(event)) {
                try {
                    handler(eventType.cast(event))
                } catch (failure: Throwable) {
                    owner.logger.warning(
                        "[KSL] ${KSLError.ADDON_LISTENER_FAILED.tag} " +
                            "listen<${eventType.simpleName}>: ${failure::class.simpleName}: ${failure.message}"
                    )
                }
            }
        }
        Bukkit.getPluginManager().registerEvent(
            eventType,
            listener,
            priority,
            executor,
            owner,
            ignoreCancelled
        )
        return own(AutoCloseable { HandlerList.unregisterAll(listener) })
    }

    /** Reified Kotlin shortcut for [listen]. */
    inline fun <reified T : Event> listen(
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        noinline handler: (T) -> Unit
    ): AutoCloseable = listen(T::class.java, priority, ignoreCancelled, handler)

    /** Schedules one task on the server thread and cancels it when the scope closes. */
    fun runSync(block: () -> Unit): BukkitTask =
        track(Bukkit.getScheduler().runTask(owner, Runnable(block)))

    /** Schedules one task asynchronously; do not access Bukkit world/entity/inventory state. */
    fun runAsync(block: () -> Unit): BukkitTask =
        track(Bukkit.getScheduler().runTaskAsynchronously(owner, Runnable(block)))

    /** Schedules a server-thread task after [delayTicks]. */
    fun runSyncLater(delayTicks: Long, block: () -> Unit): BukkitTask {
        require(delayTicks >= 0L) { "delayTicks must not be negative" }
        return track(Bukkit.getScheduler().runTaskLater(owner, Runnable(block), delayTicks))
    }

    /** Schedules an async task after [delayTicks]. */
    fun runAsyncLater(delayTicks: Long, block: () -> Unit): BukkitTask {
        require(delayTicks >= 0L) { "delayTicks must not be negative" }
        return track(Bukkit.getScheduler().runTaskLaterAsynchronously(owner, Runnable(block), delayTicks))
    }

    /** Repeats a task on the server thread. [periodTicks] must be positive. */
    fun runSyncRepeating(
        initialDelayTicks: Long = 0L,
        periodTicks: Long,
        block: () -> Unit
    ): BukkitTask {
        require(initialDelayTicks >= 0L) { "initialDelayTicks must not be negative" }
        require(periodTicks > 0L) { "periodTicks must be positive" }
        return track(
            Bukkit.getScheduler().runTaskTimer(owner, Runnable(block), initialDelayTicks, periodTicks)
        )
    }

    /** Repeats a task asynchronously. Bukkit world/entity/inventory access is not safe in [block]. */
    fun runAsyncRepeating(
        initialDelayTicks: Long = 0L,
        periodTicks: Long,
        block: () -> Unit
    ): BukkitTask {
        require(initialDelayTicks >= 0L) { "initialDelayTicks must not be negative" }
        require(periodTicks > 0L) { "periodTicks must be positive" }
        return track(
            Bukkit.getScheduler().runTaskTimerAsynchronously(owner, Runnable(block), initialDelayTicks, periodTicks)
        )
    }

    private fun track(task: BukkitTask): BukkitTask {
        own(AutoCloseable { task.cancel() })
        return task
    }

    /**
     * Closes every owned resource in reverse registration order.
     * All resources are attempted even when one close operation fails.
     */
    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            resources.asReversed().toList().also { resources.clear() }
        }

        var failure: Throwable? = null
        pending.forEach { resource ->
            try {
                resource.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure?.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let {
            throw IllegalStateException("One or more KSL addon resources failed to close", it)
        }
    }

    companion object {
        /** Java-friendly factory for addon integrations. */
        @JvmStatic
        fun create(api: KSLAPI, schedulerId: String = "ksl"): KSLAddonScope {
            val plugin = api.kslPlugin
            val ksl = plugin as? KotlinScriptLoaderPlugin
                ?: throw IllegalArgumentException("KSLAddonScope requires the official KotlinScriptLoader plugin")
            val scheduler = ksl.createTaskScheduler(schedulerId)
            return createOwnedScope(api, plugin, scheduler, emptyList())
        }

        /** Registers and owns an addon-specific scheduler factory and engine instance. */
        @JvmStatic
        fun create(api: KSLAPI, schedulerId: String, factory: KSLTaskSchedulerFactory): KSLAddonScope {
            val plugin = api.kslPlugin
            val ksl = plugin as? KotlinScriptLoaderPlugin
                ?: throw IllegalArgumentException("KSLAddonScope requires the official KotlinScriptLoader plugin")
            val registration = ksl.registerSchedulerFactory(schedulerId, factory)
            try {
                val scheduler = ksl.createTaskScheduler(schedulerId)
                return createOwnedScope(api, plugin, scheduler, listOf(registration))
            } catch (failure: Throwable) {
                runCatching { registration.close() }
                throw failure
            }
        }

        private fun createOwnedScope(
            api: KSLAPI,
            plugin: Plugin,
            scheduler: KSLTaskScheduler,
            registrations: List<AutoCloseable>
        ): KSLAddonScope {
            val scope = KSLAddonScope(
                plugin,
                { topic, handler -> api.subscribe(topic, handler) },
                KSLTaskScope(scheduler, "addon:${plugin.name.lowercase()}")
            )
            registrations.forEach(scope::own)
            scope.own(AutoCloseable { scheduler.close() })
            return scope
        }
    }
}
