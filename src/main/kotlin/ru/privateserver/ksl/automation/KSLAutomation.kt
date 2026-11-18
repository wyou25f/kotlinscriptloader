package ru.privateserver.ksl.automation

import org.bukkit.configuration.file.YamlConfiguration
import ru.privateserver.ksl.api.KSLAddonScope
import ru.privateserver.ksl.kslshu.KSLTaskContext
import ru.privateserver.ksl.kslshu.KSLTaskHandle
import ru.privateserver.ksl.kslshu.KSLTaskRepeatMode
import ru.privateserver.ksl.rt.KSLWorkPriority
import ru.privateserver.ksl.std.toml.KSLTomlCodec
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

enum class KSLAutomationFormat { YAML, TOML }

data class KSLAutomationContext(
    val id: String,
    val arguments: Map<String, Any?>,
    val task: KSLTaskContext
)

fun interface KSLAutomationAction {
    fun execute(context: KSLAutomationContext)
}

/** Only explicitly registered handlers can be invoked by declarative config. */
class KSLAutomationActionRegistry : AutoCloseable {
    private val actions = ConcurrentHashMap<String, KSLAutomationAction>()

    fun register(id: String, action: KSLAutomationAction): AutoCloseable {
        val key = normalizeId(id, "handler")
        require(actions.putIfAbsent(key, action) == null) {
            "An automation handler named '" + key + "' is already registered"
        }
        return AutoCloseable { actions.remove(key, action) }
    }

    fun registeredIds(): Set<String> = actions.keys.toSortedSet()

    internal fun resolve(id: String): KSLAutomationAction =
        actions[normalizeId(id, "handler")]
            ?: throw IllegalArgumentException("Unknown automation handler '" + id + "'")

    override fun close() = actions.clear()

    companion object {
        internal fun normalizeId(value: String, label: String): String {
            val normalized = value.trim().lowercase()
            require(normalized.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) {
                label + " ID must use 1-64 lowercase letters, digits, '.', '_' or '-' and start with a letter"
            }
            return normalized
        }
    }
}

internal sealed interface KSLAutomationSchedule {
    data class Once(val delayMillis: Long) : KSLAutomationSchedule
    data class Every(val intervalMillis: Long, val initialDelayMillis: Long, val mode: KSLTaskRepeatMode) :
        KSLAutomationSchedule
}

internal data class KSLAutomationDefinition(
    val id: String,
    val enabled: Boolean,
    val schedule: KSLAutomationSchedule,
    val handlerId: String,
    val arguments: Map<String, Any?>
)

data class KSLAutomationReloadResult(val active: Int, val disabled: Int)

/** Bounded YAML/TOML parser. Version 1 supports at most 256 jobs and 30-day delays. */
object KSLAutomationConfigParser {
    private const val MAX_SOURCE_CHARS = 1_048_576
    private const val MAX_AUTOMATIONS = 256
    private const val MAX_DELAY_MILLIS = 30L * 24 * 60 * 60 * 1_000

    internal fun parse(source: String, format: KSLAutomationFormat): List<KSLAutomationDefinition> {
        require(source.length <= MAX_SOURCE_CHARS) { "Automation config exceeds the 1 Mi-character limit" }
        val rawRoot: Map<*, *> = when (format) {
            KSLAutomationFormat.YAML -> {
                val yaml = YamlConfiguration()
                yaml.loadFromString(source)
                yaml.getValues(false)
            }
            KSLAutomationFormat.TOML -> KSLTomlCodec.parse(source)
        }
        val root = rawRoot.stringMap("root")
        require(root.keys.all { it == "version" || it == "automations" }) {
            "Automation config accepts only 'version' and 'automations' at the root"
        }
        require(root["version"].exactLong("version") == 1L) {
            "Unsupported automation config version; expected 1"
        }
        val entries = root["automations"] as? List<*>
            ?: throw IllegalArgumentException("'automations' must be a list")
        require(entries.size <= MAX_AUTOMATIONS) {
            "Automation config may contain at most " + MAX_AUTOMATIONS + " entries"
        }

        val seen = HashSet<String>()
        return entries.mapIndexed { index, raw ->
            val item = raw.stringMap("automations[" + index + "]")
            require(item.keys.all { it in setOf("id", "enabled", "schedule", "action") }) {
                "Unknown key in automations[" + index + "]"
            }
            val id = KSLAutomationActionRegistry.normalizeId(
                item["id"] as? String ?: error("automations[" + index + "].id must be a string"),
                "automation"
            )
            require(seen.add(id)) { "Duplicate automation ID '" + id + "'" }
            val enabled = item["enabled"]?.let {
                it as? Boolean ?: throw IllegalArgumentException("automations[" + index + "].enabled must be boolean")
            } ?: true

            val scheduleValues = item["schedule"].stringMap("automations[" + index + "].schedule")
            val schedule = parseSchedule(scheduleValues, index)
            val actionValues = item["action"].stringMap("automations[" + index + "].action")
            require(actionValues.keys.all { it == "handler" || it == "args" }) {
                "Unknown key in automations[" + index + "].action"
            }
            val handler = actionValues["handler"] as? String
                ?: throw IllegalArgumentException("automations[" + index + "].action.handler must be a string")
            KSLAutomationActionRegistry.normalizeId(handler, "handler")
            val arguments = (actionValues["args"] ?: emptyMap<String, Any?>())
                .stringMap("automations[" + index + "].action.args")
            validateArguments(arguments, "automations[" + index + "].action.args", 0)
            KSLAutomationDefinition(id, enabled, schedule, handler.trim().lowercase(), freezeMap(arguments))
        }
    }

    private fun parseSchedule(values: Map<String, Any?>, index: Int): KSLAutomationSchedule {
        val type = values["type"] as? String
            ?: throw IllegalArgumentException("automations[" + index + "].schedule.type must be a string")
        return when (type.lowercase()) {
            "once" -> {
                require(values.keys.all { it == "type" || it == "delay-ms" }) {
                    "Unknown one-shot schedule key in automations[" + index + "]"
                }
                KSLAutomationSchedule.Once(boundedMillis(values["delay-ms"], "delay-ms", 1L))
            }
            "every" -> {
                require(values.keys.all { it in setOf("type", "interval-ms", "initial-delay-ms", "mode") }) {
                    "Unknown repeating schedule key in automations[" + index + "]"
                }
                val interval = boundedMillis(values["interval-ms"], "interval-ms", 50L)
                val initial = values["initial-delay-ms"]?.let {
                    boundedMillis(it, "initial-delay-ms", 1L)
                } ?: interval
                val mode = when ((values["mode"] as? String ?: "fixed-delay").lowercase()) {
                    "fixed-delay" -> KSLTaskRepeatMode.FIXED_DELAY
                    "fixed-rate" -> KSLTaskRepeatMode.FIXED_RATE
                    else -> throw IllegalArgumentException("schedule.mode must be 'fixed-delay' or 'fixed-rate'")
                }
                KSLAutomationSchedule.Every(interval, initial, mode)
            }
            else -> throw IllegalArgumentException("schedule.type must be 'once' or 'every'")
        }
    }

    private fun boundedMillis(value: Any?, name: String, minimum: Long): Long {
        val number = value.exactLong(name)
        require(number in minimum..MAX_DELAY_MILLIS) {
            name + " must be between " + minimum + " and " + MAX_DELAY_MILLIS + " milliseconds"
        }
        return number
    }

    private fun Any?.exactLong(name: String): Long = when (this) {
        is Byte, is Short, is Int, is Long -> (this as Number).toLong()
        else -> throw IllegalArgumentException(name + " must be an integer")
    }

    private fun Any?.stringMap(name: String): Map<String, Any?> {
        val source = this as? Map<*, *>
            ?: throw IllegalArgumentException(name + " must be a table/object")
        val result = linkedMapOf<String, Any?>()
        source.forEach { (key, value) ->
            require(key is String) { name + " keys must be strings" }
            result[key] = value
        }
        return result
    }

    private fun validateArguments(value: Any?, path: String, depth: Int) {
        require(depth <= 8) { path + " exceeds the maximum nesting depth" }
        when (value) {
            null -> throw IllegalArgumentException(path + " cannot contain null")
            is String, is Boolean, is Byte, is Short, is Int, is Long -> Unit
            is Float -> require(value.isFinite()) { path + " contains a non-finite number" }
            is Double -> require(value.isFinite()) { path + " contains a non-finite number" }
            is Number -> Unit
            is Map<*, *> -> {
                require(value.size <= 128) { path + " contains too many keys" }
                value.forEach { (key, child) ->
                    require(key is String) { path + " keys must be strings" }
                    validateArguments(child, path + "." + key, depth + 1)
                }
            }
            is List<*> -> {
                require(value.size <= 256) { path + " list is too large" }
                value.forEachIndexed { index, child -> validateArguments(child, path + "[" + index + "]", depth + 1) }
            }
            else -> throw IllegalArgumentException(path + " contains unsupported value type " + value.javaClass.simpleName)
        }
    }

    private fun freezeMap(source: Map<String, Any?>): Map<String, Any?> =
        source.mapValues { (_, value) -> freeze(value) }

    private fun freeze(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (it.key as String) to freeze(it.value) }.toMap()
        is List<*> -> value.map(::freeze)
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        else -> value
    }
}

/**
 * Owner-scoped automation engine. Config and handler IDs are validated before
 * replacement. Scheduling failure cancels candidates and preserves the old set.
 * Handlers run on KSL's worker thread; Bukkit world/entity/inventory state must
 * be accessed only after switching to the server main thread.
 */
class KSLAutomationEngine(
    private val scope: KSLAddonScope,
    private val actions: KSLAutomationActionRegistry
) : AutoCloseable {
    private data class Active(val handle: KSLTaskHandle)
    private val lock = Any()
    private var active = linkedMapOf<String, Active>()
    private var closed = false

    val activeIds: Set<String>
        get() = synchronized(lock) { active.keys.toSortedSet() }

    fun reload(source: String, format: KSLAutomationFormat): KSLAutomationReloadResult {
        val definitions = KSLAutomationConfigParser.parse(source, format)
        val enabled = definitions.filter { it.enabled }
        val resolved = enabled.map { it to actions.resolve(it.handlerId) }

        synchronized(lock) {
            check(!closed) { "KSL automation engine is closed" }
            val candidate = linkedMapOf<String, Active>()
            try {
                resolved.forEach { (definition, handler) ->
                    val callback: (KSLTaskContext) -> Unit = { task ->
                        handler.execute(KSLAutomationContext(definition.id, definition.arguments, task))
                    }
                    val handle = when (val schedule = definition.schedule) {
                        is KSLAutomationSchedule.Once ->
                            scope.schedule(Duration.ofMillis(schedule.delayMillis), KSLWorkPriority.NORMAL, callback)
                        is KSLAutomationSchedule.Every ->
                            scope.repeat(
                                Duration.ofMillis(schedule.intervalMillis),
                                Duration.ofMillis(schedule.initialDelayMillis),
                                schedule.mode,
                                KSLWorkPriority.NORMAL,
                                callback
                            )
                    }
                    candidate[definition.id] = Active(handle)
                }
            } catch (failure: Throwable) {
                candidate.values.forEach { runCatching { it.handle.cancel(true) } }
                throw failure
            }

            val previous = active
            active = candidate
            candidate.forEach { (id, entry) ->
                entry.handle.completion.whenComplete { _, _ ->
                    synchronized(lock) {
                        if (active[id] === entry) active.remove(id)
                    }
                }
            }
            previous.values.forEach { runCatching { it.handle.cancel(true) } }
            return KSLAutomationReloadResult(active.size, definitions.size - enabled.size)
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            active.values.toList().also { active.clear() }
        }
        pending.forEach { runCatching { it.handle.cancel(true) } }
    }
}
