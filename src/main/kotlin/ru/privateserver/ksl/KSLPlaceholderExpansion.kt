package ru.privateserver.ksl

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

class KSLPlaceholderExpansion(private val plugin: KotlinScriptLoaderPlugin) : PlaceholderExpansion() {

    override fun getIdentifier() = "ksl"
    override fun getAuthor() = plugin.description.authors.firstOrNull() ?: "KSL"
    override fun getVersion() = plugin.description.version
    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val key = params.lowercase()
        val resolver = plugin.placeholders.get(key) ?: return ""
        val target = player ?: return ""
        // A throwing resolver would otherwise surface inside PlaceholderAPI on every refresh (scoreboards, tab lists).
        return try {
            resolver(target) ?: ""
        } catch (ex: Throwable) {
            val owner = plugin.placeholders.ownerOf(key)?.removePrefix("script:") ?: "?"
            KSLErrors.log(plugin, owner, "placeholder %ksl_$params%", ex, KSLError.PLACEHOLDER_RESOLVER_FAILED)
            ""
        }
    }
}
