package ru.privateserver.ksl.addon

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import ru.privateserver.ksl.BukkitScriptContext
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon
import ru.privateserver.ksl.KSLContextExtension
import ru.privateserver.ksl.KSLEffectShape
import ru.privateserver.ksl.KSLEntityAttributes
import ru.privateserver.ksl.KSLHook
import ru.privateserver.ksl.KSLQuickTable
import ru.privateserver.ksl.KSLShapeFactory
import ru.privateserver.ksl.KSLTemplateParser
import ru.privateserver.ksl.KotlinScriptLoaderPlugin
import ru.privateserver.ksl.service
import kotlin.math.cos
import kotlin.math.sin

// Short names for people who write KSL addons. Everything is a thin wrapper over KSLAPI or public classes,
// so the long forms keep working. Import with: import ru.privateserver.ksl.addon.*

// ---- addon skeletons ----

// An addon without a class of its own: simpleAddon("my-addon", "1.0") { api -> api.provide("x", Foo()) }
// The block parameters are named differently from onLoad/onUnload on purpose: inside the object those names
// would resolve to the members themselves and recurse forever.
fun simpleAddon(
    id: String,
    version: String,
    description: String = "",
    requires: List<String> = emptyList(),
    needsApi: Int = 1,
    unload: () -> Unit = {},
    load: (KSLAPI) -> Unit
): KSLAddon = object : KSLAddon {
    override val addonId = id
    override val addonVersion = version
    override val addonDescription = description
    override val dependsOn = requires
    override val minApiVersion = needsApi
    override fun onLoad(api: KSLAPI) = load(api)
    override fun onUnload() = unload()
}

// Base for hooks: abstract class MyHook : KSLBaseHook("myhook", "MyPlugin")
abstract class KSLBaseHook(override val hookId: String, override val pluginName: String) : KSLHook

// ---- services ----

fun KSLAPI.provide(key: String, instance: Any) = registerService(key, instance)

fun KSLAPI.revoke(key: String) = unregisterService(key)

fun KSLAPI.hasService(key: String): Boolean = getService(key) != null

inline fun <reified T : Any> KSLAPI.serviceOr(key: String, default: T): T = service<T>(key) ?: default

// ---- addons ----

fun KSLAPI.imports(vararg packages: String) = addDefaultImports(*packages)

fun KSLAPI.whenReady(addonId: String, callback: (KSLAddon) -> Unit) = onAddonReady(addonId, callback)

fun KSLAPI.isLoaded(addonId: String): Boolean = isAddonLoaded(addonId)

fun KSLAPI.findAddon(addonId: String): KSLAddon? = registeredAddons().firstOrNull { it.addonId == addonId }

fun KSLAPI.addonIds(): List<String> = registeredAddons().map { it.addonId }

// Fails fast with a readable message instead of a NoSuchMethodError later.
fun KSLAPI.requireApi(version: Int) {
    check(apiVersion >= version) { "Нужен KSL API v$version, установлен v$apiVersion — обнови KotlinScriptLoader" }
}

val KSLAPI.kslVersion: String get() = kslPlugin.description.version

fun KSLAPI.reload(): Pair<Int, Int> = reloadScripts()

// Per-script state: onCreated runs for each script that loads, onDestroyed when it unloads.
fun KSLAPI.contextExtension(id: String, onCreated: (BukkitScriptContext) -> Unit = {}, onDestroyed: (String) -> Unit = {}) {
    registerContextExtension(object : KSLContextExtension {
        override val extensionId = id
        override fun onContextCreated(context: BukkitScriptContext) = onCreated(context)
        override fun onContextDestroyed(scriptName: String) = onDestroyed(scriptName)
    })
}

// ---- hooks ----

fun KSLAPI.hasHook(hookId: String): Boolean = getHook(hookId) != null

fun KSLAPI.hookIds(): List<String> = hooks().map { it.hookId }

// Registers the hook only when the target plugin is enabled; a failing factory is logged, not thrown.
fun KSLAPI.hookIf(pluginName: String, factory: () -> KSLHook): Boolean {
    if (!isPluginEnabled(pluginName)) return false
    return try {
        registerHook(factory())
        true
    } catch (ex: Throwable) {
        kslPlugin.logger.warning("[KSL] хук для $pluginName не создан: ${ex::class.simpleName}: ${ex.message}")
        false
    }
}

// ---- engines ----

fun KSLAPI.shape(name: String, factory: KSLShapeFactory) = registerEffectShape(name, factory)

fun KSLAPI.entityAttribute(key: String, handler: (LivingEntity, String) -> Unit) = registerEntityAttribute(key, handler)

fun shapeOf(points: (tick: Long, center: Location) -> List<Vector>): KSLEffectShape = KSLEffectShape { tick, center -> points(tick, center) }

// Evenly spaced points on a horizontal ring, ready to use inside a shape.
fun ringPoints(radius: Double, count: Int, y: Double = 0.0): List<Vector> {
    val n = count.coerceIn(1, 360)
    return (0 until n).map { i ->
        val angle = 2.0 * Math.PI * i / n
        Vector(cos(angle) * radius, y, sin(angle) * radius)
    }
}

fun parseTemplate(template: String): Map<String, String> = KSLTemplateParser.parse(template)

fun LivingEntity.attr(name: String, value: Double): Boolean = KSLEntityAttributes.setBase(this, name, value)

fun LivingEntity.attrBase(name: String): Double? = KSLEntityAttributes.getBase(this, name)

// ---- messaging between addons and scripts ----

fun KSLAPI.on(topic: String, handler: (Any?) -> Unit): AutoCloseable = subscribe(topic, handler)

fun KSLAPI.emit(topic: String, payload: Any? = null): Int = publish(topic, payload)

// ---- storage ----

// Addon storage in KSL's SQLite. Prefix the name with your addon id so tables do not collide.
fun KSLAPI.table(name: String): KSLQuickTable =
    (kslPlugin as? KotlinScriptLoaderPlugin)?.tableStore?.table(name)
        ?: error("KSL ещё не инициализирован — table() можно вызывать начиная с onLoad")

// ---- logging ----

fun KSLAPI.log(message: String) = kslPlugin.logger.info("[KSL] $message")

fun KSLAPI.warn(message: String) = kslPlugin.logger.warning("[KSL] $message")

fun KSLAPI.severe(message: String) = kslPlugin.logger.severe("[KSL] $message")

// ---- scheduling (not cancelled automatically when the addon unloads; keep the task and cancel it in onUnload) ----

fun KSLAPI.sync(block: () -> Unit): BukkitTask = Bukkit.getScheduler().runTask(kslPlugin, Runnable { block() })

fun KSLAPI.async(block: () -> Unit): BukkitTask = Bukkit.getScheduler().runTaskAsynchronously(kslPlugin, Runnable { block() })

fun KSLAPI.later(ticks: Long, block: () -> Unit): BukkitTask =
    Bukkit.getScheduler().runTaskLater(kslPlugin, Runnable { block() }, ticks)

fun KSLAPI.every(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): BukkitTask =
    Bukkit.getScheduler().runTaskTimer(kslPlugin, Runnable { block() }, initialDelay, ticks.coerceAtLeast(1L))

// ---- Bukkit events ----

// Closing the returned handle unregisters the listener; keep it and close it in onUnload.
inline fun <reified T : Event> KSLAPI.listen(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    crossinline action: (T) -> Unit
): AutoCloseable {
    val listener = object : Listener {}
    val owner = kslPlugin
    val executor = EventExecutor { _, event ->
        if (event is T) {
            try {
                action(event)
            } catch (ex: Throwable) {
                owner.logger.warning("[KSL] listen<${T::class.simpleName}>: ${ex::class.simpleName}: ${ex.message}")
            }
        }
    }
    Bukkit.getPluginManager().registerEvent(T::class.java, listener, priority, executor, owner, ignoreCancelled)
    return AutoCloseable { HandlerList.unregisterAll(listener) }
}
