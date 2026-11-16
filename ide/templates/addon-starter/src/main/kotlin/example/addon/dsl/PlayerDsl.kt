package example.addon.dsl

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

fun Player.exampleWelcome() {
    sendMessage(Component.text("Welcome to the server!"))
}
