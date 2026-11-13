package ru.privateserver.ksl

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

private val COMMAND_LABEL_PATTERN = Regex("[a-z0-9._-]+")

private fun normalizeCommandLabel(label: String): String {
    val normalized = label.trim().lowercase(Locale.ROOT)
    require(COMMAND_LABEL_PATTERN.matches(normalized)) {
        "Некорректное имя команды '$label': разрешены латинские буквы, цифры, '.', '_' и '-'."
    }
    return normalized
}

/**
 * Normalizes command aliases. Each entry may contain one alias or a comma/space-separated
 * group, which keeps configuration-driven command declarations concise.
 */
internal fun normalizeCommandAliases(
    primaryName: String,
    aliases: Iterable<String>,
    onWarning: (String) -> Unit = {}
): List<String> {
    val primary = normalizeCommandLabel(primaryName)
    val normalizedAliases = LinkedHashSet<String>()

    aliases.forEach { rawEntry ->
        rawEntry.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.forEach { rawAlias ->
            val alias = rawAlias.trim().lowercase(Locale.ROOT)
            when {
                !COMMAND_LABEL_PATTERN.matches(alias) ->
                    onWarning("Некорректный alias '$rawAlias' для команды '$primary'.")
                alias == primary ->
                    onWarning("Alias '$rawAlias' совпадает с именем команды '$primary' и пропущен.")
                !normalizedAliases.add(alias) ->
                    onWarning("Повторяющийся alias '$rawAlias' для команды '$primary' пропущен.")
            }
        }
    }
    return normalizedAliases.toList()
}

class DynamicCommand(
    name: String,
    aliases: List<String>,
    val scriptName: String,
    private val plugin: KotlinScriptLoaderPlugin,
    private val requiredPermission: String?,
    private val tabCompleter: ((CommandSender, Array<String>) -> List<String>)?,
    private val playersOnly: Boolean,
    private val action: (CommandSender, Array<String>) -> Unit
) : Command(normalizeCommandLabel(name)) {

    init {
        val primaryName = normalizeCommandLabel(name)
        this.aliases = normalizeCommandAliases(primaryName, aliases) { warning ->
            plugin.logger.warning("[$scriptName] $warning")
        }
        this.description = "Команда скрипта $scriptName"
        if (requiredPermission != null) this.permission = requiredPermission
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        if (playersOnly && sender !is Player) {
            sender.sendMessage("Эта команда доступна только игрокам.")
            return true
        }
        if (requiredPermission != null && !sender.hasPermission(requiredPermission)) {
            sender.sendMessage("§cУ тебя нет прав на эту команду.")
            return true
        }
        try {
            plugin.measureScriptExecution(scriptName, "command:/$commandLabel") {
                action(sender, args)
            }
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "command '/$commandLabel'", ex, KSLError.COMMAND_EXECUTE_FAILED)
            sender.sendMessage("§cПроизошла ошибка при выполнении команды. Администратор уже уведомлён в консоли.")
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> {
        val completer = tabCompleter ?: return super.tabComplete(sender, alias, args)
        if (playersOnly && sender !is Player) return emptyList()
        if (requiredPermission != null && !sender.hasPermission(requiredPermission)) return emptyList()
        return try {
            completer(sender, args)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "tabComplete '/$alias'", ex, KSLError.COMMAND_TAB_FAILED)
            emptyList()
        }
    }
}
