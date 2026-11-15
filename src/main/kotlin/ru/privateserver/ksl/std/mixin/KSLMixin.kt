package ru.privateserver.ksl.std.mixin

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import ru.privateserver.ksl.BukkitScriptContext
import java.util.Collections
import java.util.WeakHashMap

/** A lifecycle-safe composition of KSL event handlers and scheduled callbacks. */
class KSLMixin internal constructor(
    val id: String,
    val description: String,
    internal val ownerScript: String,
    internal val installers: List<BukkitScriptContext.() -> Unit>
) {
    private val installedContexts = Collections.synchronizedMap(WeakHashMap<BukkitScriptContext, Boolean>())
    val hookCount: Int get() = installers.size

    internal fun installInto(context: BukkitScriptContext): Boolean {
        require(context.scriptName == ownerScript) {
            "KSL mixin '$id' belongs to script '$ownerScript' and cannot be installed by '${context.scriptName}'"
        }
        synchronized(installedContexts) {
            if (installedContexts.containsKey(context)) return false
            installedContexts[context] = true
        }
        try {
            installers.forEach { installer -> installer.invoke(context) }
        } catch (failure: Throwable) {
            synchronized(installedContexts) { installedContexts.remove(context) }
            throw failure
        }
        context.onUnload { synchronized(installedContexts) { installedContexts.remove(context) } }
        return true
    }
}

/** Builder for reusable, script-local behavior groups. */
class KSLMixinBuilder internal constructor(private val id: String, private val ownerScript: String) {
    private val installers = mutableListOf<BukkitScriptContext.() -> Unit>()
    private var descriptionText = ""

    fun description(value: String) {
        require(value.length <= 240) { "KSL mixin description must not exceed 240 characters" }
        descriptionText = value.trim()
    }

    /** Adds a custom KSL context installation step. */
    fun install(action: BukkitScriptContext.() -> Unit) { installers += action }

    /** Adds an event listener which is unregistered with its owning script. */
    inline fun <reified T : Event> on(
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        crossinline action: T.() -> Unit
    ) {
        val selectedPriority = priority
        val skipCancelled = ignoreCancelled
        addInstaller {
            val context = this
            context.onEvent<T>(selectedPriority, skipCancelled) { action.invoke(this) }
        }
    }

    /** Adds a repeating main-thread callback managed by the owning script. */
    fun every(periodTicks: Long, initialDelayTicks: Long = 0L, action: BukkitScriptContext.() -> Unit) {
        require(periodTicks > 0L) { "KSL mixin every() periodTicks must be positive" }
        require(initialDelayTicks >= 0L) { "KSL mixin every() initialDelayTicks must not be negative" }
        addInstaller { val context = this; context.every(periodTicks, initialDelayTicks) { action.invoke(context) } }
    }

    /** Adds a one-shot main-thread callback managed by the owning script. */
    fun after(delayTicks: Long, action: BukkitScriptContext.() -> Unit) {
        require(delayTicks >= 0L) { "KSL mixin after() delayTicks must not be negative" }
        addInstaller { val context = this; context.delay(delayTicks) { action.invoke(context) } }
    }

    @PublishedApi
    internal fun addInstaller(installer: BukkitScriptContext.() -> Unit) { installers += installer }

    internal fun build(): KSLMixin {
        val normalized = id.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "KSL mixin id must use 1-64 lowercase letters, numbers, '.', '_' or '-': '$id'"
        }
        require(installers.isNotEmpty()) { "KSL mixin '$normalized' must contain at least one hook" }
        return KSLMixin(normalized, descriptionText, ownerScript, installers.toList())
    }
}

/** Creates a named group of reusable handlers for the current script. */
fun BukkitScriptContext.mixin(id: String, configure: KSLMixinBuilder.() -> Unit): KSLMixin =
    KSLMixinBuilder(id, scriptName).apply(configure).build()
/** Installs a mixin once in this context; registrations follow the script lifecycle. */
fun BukkitScriptContext.applyMixin(mixin: KSLMixin): Boolean = mixin.installInto(this)
/** Short alias for [applyMixin]. */
fun BukkitScriptContext.useMixin(mixin: KSLMixin): Boolean = applyMixin(mixin)