package ru.privateserver.ksl

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class KSLDiscordHook(private val plugin: KotlinScriptLoaderPlugin) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val webhooks: Map<String, String> = run {
        val section = plugin.config.getConfigurationSection("discord.webhooks") ?: return@run emptyMap()
        section.getKeys(false)
            .associateWith { section.getString(it, "") ?: "" }
            .filterValues { it.startsWith("https://discord.com/api/webhooks/") || it.startsWith("https://discordapp.com/api/webhooks/") }
    }

    fun isConfigured(channel: String) = webhooks.containsKey(channel)

    fun send(channel: String, content: String) {
        val url = webhooks[channel] ?: run {
            plugin.logger.warning("[Discord] Канал '$channel' не настроен в config.yml")
            return
        }
        post(url, """{"content":${content.asJsonString()}}""")
    }

    fun sendEmbed(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = ""
    ) {
        val url = webhooks[channel] ?: run {
            plugin.logger.warning("[Discord] Канал '$channel' не настроен в config.yml")
            return
        }
        val footerBlock = if (footer.isNotBlank()) ""","footer":{"text":${footer.asJsonString()}}""" else ""
        val json = """{"embeds":[{"title":${title.asJsonString()},"description":${description.asJsonString()},"color":$color$footerBlock}]}"""
        post(url, json)
    }

    fun channels(): Set<String> = webhooks.keys

    private fun post(url: String, json: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("User-Agent", "KotlinScriptLoader/${plugin.description.version}")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(10))
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .whenComplete { response, ex ->
                if (ex != null) {
                    plugin.logger.warning("[Discord] Ошибка отправки в '$url': ${ex.message}")
                } else if (response.statusCode() !in 200..299) {
                    plugin.logger.warning("[Discord] HTTP ${response.statusCode()} при отправке вебхука")
                }
            }
    }

    private fun String.asJsonString(): String =
        "\"${replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
}
