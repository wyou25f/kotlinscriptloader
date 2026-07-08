package ru.privateserver.ksl

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DynamicCommand(
    name: String,
    aliases: List<String>,
    val scriptName: String,
    private val action: (Player, Array<String>) -> Unit
) : Command(name) {

    init {
        this.aliases = aliases
        this.description = "Команда скрипта $scriptName"
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Эта команда доступна только игрокам.")
            return true
        }
        action(sender, args)
        return true
    }
}
