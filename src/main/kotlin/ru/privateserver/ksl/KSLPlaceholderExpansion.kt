package ru.privateserver.ksl

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

class KSLPlaceholderExpansion(private val plugin: KotlinScriptLoaderPlugin) : PlaceholderExpansion() {

    override fun getIdentifier() = "ksl"
    override fun getAuthor() = plugin.description.authors.firstOrNull() ?: "KSL"
    override fun getVersion() = plugin.description.version
    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val resolver = plugin.placeholders[params] ?: return ""
        return player?.let { resolver(it) } ?: ""
    }
}
