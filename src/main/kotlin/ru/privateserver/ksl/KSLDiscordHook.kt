package ru.privateserver.ksl

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// JSON string literal for the webhook payload. Control characters below 0x20 must be escaped too,
// otherwise Discord answers 400 and the message is lost.
internal fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2).append('"')
    for (c in value) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
        }
    }
    return out.append('"').toString()
}

class KSLDiscordHook(private val plugin: KotlinScriptLoaderPlugin) {

    private companion object {
        // Discord's hard limits; anything longer is rejected with HTTP 400.
        const val MAX_CONTENT = 2000
        const val MAX_TITLE = 256
        const val MAX_DESCRIPTION = 4096
        const val MAX_FOOTER = 2048
        const val MAX_RETRIES = 2
        // Text from players ends up in messages; without this "@everyone" in a chat line would ping the whole server.
        const val NO_MENTIONS = ",\"allowed_mentions\":{\"parse\":[]}"
    }

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

    // allowMentions = true lets @everyone/@here/role mentions through.
    fun send(channel: String, content: String, allowMentions: Boolean = false) {
        val url = webhooks[channel] ?: run {
            plugin.logger.warning("[Discord] ${KSLError.DISCORD_NOT_CONFIGURED.tag} Канал '$channel' не настроен в config.yml")
            return
        }
        post(channel, url, "{\"content\":${jsonString(content.take(MAX_CONTENT))}${if (allowMentions) "" else NO_MENTIONS}}")
    }

    fun sendEmbed(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = "",
        allowMentions: Boolean = false
    ) {
        val url = webhooks[channel] ?: run {
            plugin.logger.warning("[Discord] ${KSLError.DISCORD_NOT_CONFIGURED.tag} Канал '$channel' не настроен в config.yml")
            return
        }
        val footerBlock = if (footer.isNotBlank()) ",\"footer\":{\"text\":${jsonString(footer.take(MAX_FOOTER))}}" else ""
        val embed = "{\"title\":${jsonString(title.take(MAX_TITLE))},\"description\":${jsonString(description.take(MAX_DESCRIPTION))}," +
            "\"color\":$color$footerBlock}"
        post(channel, url, "{\"embeds\":[$embed]${if (allowMentions) "" else NO_MENTIONS}}")
    }

    fun channels(): Set<String> = webhooks.keys

    // The webhook URL contains its secret token, so only the channel name may appear in logs.
    private fun post(channel: String, url: String, json: String, attempt: Int = 0) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("User-Agent", "KotlinScriptLoader/${plugin.description.version}")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(10))
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .whenComplete { response, ex ->
                when {
                    ex != null ->
                        plugin.logger.warning("[Discord] ${KSLError.DISCORD_SEND_FAILED.tag} Ошибка отправки в канал '$channel': ${ex.message}")
                    response.statusCode() == 429 && attempt < MAX_RETRIES -> {
                        // Rate limited: Discord says how long to wait; a dropped message is worse than a short delay.
                        val seconds = response.headers().firstValue("retry-after").orElse("1").toDoubleOrNull() ?: 1.0
                        val delayMs = (seconds * 1000).toLong().coerceIn(100L, 10_000L)
                        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                            .execute { post(channel, url, json, attempt + 1) }
                    }
                    response.statusCode() !in 200..299 ->
                        plugin.logger.warning("[Discord] ${KSLError.DISCORD_HTTP_ERROR.tag} HTTP ${response.statusCode()} при отправке в канал '$channel'")
                }
            }
    }
}
