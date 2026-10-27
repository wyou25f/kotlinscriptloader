package ru.privateserver.ksl

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DynamicCommand(
    name: String,
    aliases: List<String>,
    val scriptName: String,
    private val plugin: KotlinScriptLoaderPlugin,
    private val requiredPermission: String?,
    private val tabCompleter: ((CommandSender, Array<String>) -> List<String>)?,
    private val playersOnly: Boolean,
    private val action: (CommandSender, Array<String>) -> Unit
) : Command(name) {

    init {
        this.aliases = aliases
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
            KSLErrors.log(plugin, scriptName, "command '/$commandLabel'", ex)
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
            KSLErrors.log(plugin, scriptName, "tabComplete '/$alias'", ex)
            emptyList()
        }
    }
}
