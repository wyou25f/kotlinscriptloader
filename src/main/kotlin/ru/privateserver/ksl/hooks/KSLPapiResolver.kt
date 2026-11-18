package ru.privateserver.ksl

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.entity.Player

object KSLPapiResolver {
    fun resolve(player: Player, text: String): String =
        KSLErrors.hookSafe("PlaceholderAPI", text) { PlaceholderAPI.setPlaceholders(player, text) }
}
