package ru.privateserver.ksl.dsl

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.privateserver.ksl.BukkitScriptContext

/** Declarative command registration. Choose exactly one executor: player { } or any { }. */
class KSLCommandDslBuilder internal constructor(val name: String) {
    private val commandAliases = linkedSetOf<String>()
    private var commandPermission: String? = null
    private var playerExecutor: ((Player, Array<String>) -> Unit)? = null
    private var anyExecutor: ((CommandSender, Array<String>) -> Unit)? = null
    private var playerCompleter: ((Player, Array<String>) -> List<String>)? = null
    private var anyCompleter: ((CommandSender, Array<String>) -> List<String>)? = null

    fun aliases(vararg values: String) {
        values.forEach { require(it.isNotBlank()) { "Command aliases must not be blank" }; commandAliases += it }
    }
    fun permission(value: String) {
        require(value.isNotBlank()) { "Command permission must not be blank" }
        commandPermission = value
    }
    fun player(block: (Player, Array<String>) -> Unit) {
        check(anyExecutor == null) { "Command cannot have both player and any-sender executors" }
        playerExecutor = block
    }
    fun any(block: (CommandSender, Array<String>) -> Unit) {
        check(playerExecutor == null) { "Command cannot have both player and any-sender executors" }
        anyExecutor = block
    }
    fun tabComplete(block: (Player, Array<String>) -> List<String>) {
        check(anyCompleter == null) { "Choose either player or any-sender tab completion" }
        playerCompleter = block
    }
    fun tabCompleteAny(block: (CommandSender, Array<String>) -> List<String>) {
        check(playerCompleter == null) { "Choose either player or any-sender tab completion" }
        anyCompleter = block
    }

    internal fun validate() {
        require(name.isNotBlank()) { "Command name must not be blank" }
        require(name.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid command name '$name'" }
        require((playerExecutor == null) xor (anyExecutor == null)) {
            "Command '$name' must declare exactly one executor: player { } or any { }"
        }
        require(playerCompleter == null || anyCompleter == null) {
            "Command '$name' cannot declare both tab completion types"
        }
    }

    internal fun register(context: BukkitScriptContext) {
        validate()
        if (playerExecutor != null) {
            context.registerCommand(name, commandAliases.toList(), commandPermission, playerCompleter, playerExecutor!!)
        } else {
            context.registerCommandAny(name, commandAliases.toList(), commandPermission, anyCompleter, anyExecutor!!)
        }
    }
}

/** Register a command using a compact, declarative DSL. */
fun BukkitScriptContext.scriptCommand(name: String, block: KSLCommandDslBuilder.() -> Unit) {
    KSLCommandDslBuilder(name).apply(block).register(this)
}

/** Groups script-owned scheduled callbacks. Async callbacks must not access Bukkit world/player state. */
class KSLTaskDslBuilder internal constructor(private val context: BukkitScriptContext) {
    private val registered = ArrayList<Int>()
    fun after(ticks: Long, block: () -> Unit): Int = context.delay(ticks, block).also(registered::add)
    fun repeat(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int =
        context.every(ticks, initialDelay, block).also(registered::add)
    fun afterAsync(ticks: Long, block: () -> Unit): Int = context.delayAsync(ticks, block).also(registered::add)
    fun repeatAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int =
        context.everyAsync(ticks, initialDelay, block).also(registered::add)
    fun taskIds(): List<Int> = registered.toList()
}

/** Register related delayed and repeating work in one DSL block. */
fun BukkitScriptContext.scriptTasks(block: KSLTaskDslBuilder.() -> Unit): List<Int> =
    KSLTaskDslBuilder(this).apply(block).taskIds()
