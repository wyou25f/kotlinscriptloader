package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Optional bridge to Skript's public API.
 * Skript is discovered through its own plugin classloader, so no Skript classes are linked
 * from KSL bytecode and servers without Skript keep working normally.
 */
class KSLSkriptHook : KSLHook {
    override val hookId: String = "skript"
    override val pluginName: String = "Skript"

    private val skriptPlugin
        get() = Bukkit.getPluginManager().getPlugin(pluginName)?.takeIf { it.isEnabled }

    private fun apiClass(name: String): Class<*>? =
        KSLHookReflection.loadClass(pluginName, name)

    private fun variablesApi(): Class<*>? = apiClass("ch.njol.skript.variables.Variables")
    private fun commandsApi(): Class<*>? = apiClass("ch.njol.skript.command.Commands")
    private fun scriptLoaderApi(): Class<*>? = apiClass("ch.njol.skript.ScriptLoader")

    val isAvailable: Boolean get() = skriptPlugin != null
    val version: String?
        get() = KSLErrors.hookSafe("Skript", null) { skriptPlugin?.description?.version }

    val supportsVariables: Boolean get() = isAvailable && variablesApi() != null
    val supportsCommands: Boolean get() = isAvailable && commandsApi() != null
    val supportsScriptIntrospection: Boolean get() = isAvailable && scriptLoaderApi() != null

    fun diagnostics(): KSLSkriptDiagnostics = KSLSkriptDiagnostics(
        installed = Bukkit.getPluginManager().getPlugin(pluginName) != null,
        enabled = isAvailable,
        version = version,
        variablesApi = supportsVariables,
        commandsApi = supportsCommands,
        scriptIntrospection = supportsScriptIntrospection
    )

    /** True only for command labels registered by Skript, not arbitrary Bukkit commands. */
    fun isSkriptCommand(command: String): Boolean =
        KSLErrors.hookSafe("Skript", false) {
            val label = KSLSkriptNames.commandLabel(command) ?: return@hookSafe false
            KSLHookReflection.call(commandsApi(), "scriptCommandExists", label) as? Boolean ?: false
        }

    /** Execute a Skript-registered command. Must be called on the Bukkit server thread. */
    fun executeCommand(sender: CommandSender, command: String): Boolean =
        KSLErrors.hookSafe("Skript", false) {
            check(Bukkit.isPrimaryThread()) { "Skript commands must run on the server thread" }
            val normalized = command.trim().removePrefix("/")
            if (normalized.isBlank() || !isSkriptCommand(normalized)) return@hookSafe false
            Bukkit.dispatchCommand(sender, normalized)
        }

    fun executePlayerCommand(player: Player, command: String): Boolean = executeCommand(player, command)
    fun executeConsoleCommand(command: String): Boolean = executeCommand(Bukkit.getConsoleSender(), command)

    /** Schedules command execution on the server thread; useful from runAsync blocks. */
    fun executeCommandAsync(sender: CommandSender, command: String): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val owner = skriptPlugin
        if (owner == null) {
            result.complete(false)
        } else if (Bukkit.isPrimaryThread()) {
            result.complete(executeCommand(sender, command))
        } else {
            runCatching {
                owner.server.scheduler.runTask(owner, Runnable {
                    result.complete(executeCommand(sender, command))
                })
            }.onFailure { result.completeExceptionally(it) }
        }
        return result
    }

    /**
     * Reads a global Skript variable. List variables are returned as maps.
     * Names may be supplied as profile::coins or {profile::coins}.
     * Access is confined to the server thread.
     */
    fun getVariable(name: String): Any? =
        KSLErrors.hookSafe("Skript", null) {
            check(Bukkit.isPrimaryThread()) { "Skript variables must be accessed on the server thread" }
            KSLHookReflection.call(variablesApi(), "getVariable", KSLSkriptNames.variableName(name), null, false)
        }

    fun getVariableMap(name: String): Map<String, Any?>? =
        KSLErrors.hookSafe("Skript", null) {
            (getVariable(name) as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }
        }

    /** Stores a global variable; Skript validates and serializes the supplied value. */
    fun setVariable(name: String, value: Any?): Boolean =
        KSLErrors.hookSafe("Skript", false) {
            check(Bukkit.isPrimaryThread()) { "Skript variables must be changed on the server thread" }
            KSLHookReflection.invokeIfPresent(
                variablesApi(), "setVariable", KSLSkriptNames.variableName(name), value, null, false
            )
        }

    fun deleteVariable(name: String): Boolean =
        KSLErrors.hookSafe("Skript", false) {
            check(Bukkit.isPrimaryThread()) { "Skript variables must be changed on the server thread" }
            KSLHookReflection.invokeIfPresent(
                variablesApi(), "deleteVariable", KSLSkriptNames.variableName(name), null, false
            )
        }

    fun hasVariable(name: String): Boolean = getVariable(name) != null

    /** Snapshot of loaded script paths relative to plugins/Skript/scripts/, including .sk. */
    fun loadedScripts(): Set<String> =
        KSLErrors.hookSafe("Skript", emptySet()) {
            val scripts = KSLHookReflection.call(scriptLoaderApi(), "getLoadedScripts") as? Iterable<*>
                ?: return@hookSafe emptySet()
            val scriptsFolder = skriptPlugin?.dataFolder?.resolve("scripts")?.canonicalFile
            scripts.mapNotNullTo(linkedSetOf()) { script ->
                val config = KSLHookReflection.call(script, "getConfig")
                val file = KSLHookReflection.call(config, "getFile") as? File ?: return@mapNotNullTo null
                val relative = if (scriptsFolder != null) {
                    runCatching { file.canonicalFile.relativeTo(scriptsFolder).invariantSeparatorsPath }
                        .getOrElse { file.name }
                } else file.name
                relative.takeIf { it.endsWith(".sk", ignoreCase = true) }
            }
        }

    fun isScriptLoaded(path: String): Boolean {
        val normalized = KSLSkriptNames.scriptPath(path) ?: return false
        return loadedScripts().any { it.equals(normalized, ignoreCase = true) }
    }

    val loadedScriptCount: Int get() = loadedScripts().size

    /**
     * Requests a reload through Skript's command. True means the command was dispatched,
     * not that every line in the script parsed successfully.
     */
    fun requestReloadScript(path: String): Boolean =
        KSLErrors.hookSafe("Skript", false) {
            check(Bukkit.isPrimaryThread()) { "Skript reload commands must run on the server thread" }
            val normalized = KSLSkriptNames.scriptPath(path) ?: return@hookSafe false
            val folder = skriptPlugin?.dataFolder?.resolve("scripts") ?: return@hookSafe false
            val target = File(folder, normalized).canonicalFile
            require(target.toPath().startsWith(folder.canonicalFile.toPath())) { "Script path escapes Skript/scripts" }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "skript reload $normalized")
        }

    fun requestReloadAll(): Boolean =
        KSLErrors.hookSafe("Skript", false) {
            check(Bukkit.isPrimaryThread()) { "Skript reload commands must run on the server thread" }
            if (!isAvailable) return@hookSafe false
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "skript reload all")
        }
}

data class KSLSkriptDiagnostics(
    val installed: Boolean,
    val enabled: Boolean,
    val version: String?,
    val variablesApi: Boolean,
    val commandsApi: Boolean,
    val scriptIntrospection: Boolean
) {
    val isReady: Boolean get() = enabled && variablesApi && commandsApi && scriptIntrospection
}

internal object KSLSkriptNames {
    fun variableName(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "Skript variable name cannot be blank" }
        require('\n' !in value && '\r' !in value) { "Skript variable name cannot contain line breaks" }
        return if (value.startsWith("{") && value.endsWith("}")) value.substring(1, value.length - 1) else value
    }

    fun commandLabel(input: String): String? {
        val value = input.trim().removePrefix("/")
        if (value.isEmpty() || '\n' in value || '\r' in value) return null
        return value.substringBefore(' ').substringBefore('\t').lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    fun scriptPath(input: String): String? {
        val value = input.trim().replace('\\', '/').removePrefix("./")
        if (value.isBlank() || '\n' in value || '\r' in value || value.any(Char::isWhitespace)) return null
        val segments = value.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return if (value.endsWith(".sk", ignoreCase = true)) value else "$value.sk"
    }
}
