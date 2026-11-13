package ru.privateserver.ksl

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

private val COMMAND_LABEL_PATTERN = Regex("[a-z0-9._-]+")

private fun normalizeCommandLabel(label: String): String {
    val normalized = label.lowercase(Locale.ROOT)
    require(COMMAND_LABEL_PATTERN.matches(normalized)) {
        "Некорректное имя команды '$label': разрешены латинские буквы, цифры, '.', '_' и '-'."
    }
    return normalized
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
        val normalizedAliases = LinkedHashSet<String>()

        aliases.forEach { rawAlias ->
            val alias = rawAlias.lowercase(Locale.ROOT)
            when {
                !COMMAND_LABEL_PATTERN.matches(alias) ->
                    plugin.logger.warning("[$scriptName] Пропущен некорректный alias '$rawAlias' для команды '$primaryName'.")
                alias == primaryName ->
                    plugin.logger.warning("[$scriptName] Alias '$rawAlias' совпадает с именем команды '$primaryName' и пропущен.")
                !normalizedAliases.add(alias) ->
                    plugin.logger.warning("[$scriptName] Повторяющийся alias '$rawAlias' для команды '$primaryName' пропущен.")
            }
        }

        this.aliases = normalizedAliases.toList()
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
            action(sender, args)
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
