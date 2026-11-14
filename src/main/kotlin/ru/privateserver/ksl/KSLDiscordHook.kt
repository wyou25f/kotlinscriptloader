package ru.privateserver.ksl

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Reject malformed or non-Discord URLs before they can fail an async send call.
internal fun isDiscordWebhookUrl(value: String): Boolean = try {
    val uri = URI(value)
    val host = uri.host?.lowercase()
    uri.scheme.equals("https", ignoreCase = true) &&
        (host == "discord.com" || host == "discordapp.com") &&
        (uri.port == -1 || uri.port == 443) &&
        uri.userInfo == null &&
        uri.fragment == null &&
        uri.path.startsWith("/api/webhooks/") &&
        uri.path.removePrefix("/api/webhooks/").isNotBlank()
} catch (_: Exception) {
    false
}

// JSON string literal for the webhook payload. Control characters below 0x20 must be escaped too.
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

/** One Discord embed field. Discord limits each field name/value to 256/1024 characters. */
data class KSLDiscordEmbedField(val name: String, val value: String, val inline: Boolean = false)

class KSLDiscordHook(private val plugin: KotlinScriptLoaderPlugin) {

    private companion object {
        const val MAX_CONTENT = 2000
        const val MAX_TITLE = 256
        const val MAX_DESCRIPTION = 4096
        const val MAX_FOOTER = 2048
        const val MAX_FIELDS = 25
        const val MAX_RETRIES = 2
        const val NO_MENTIONS = ",\"allowed_mentions\":{\"parse\":[]}"
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val webhooks: Map<String, String> = run {
        val section = plugin.config.getConfigurationSection("discord.webhooks") ?: return@run emptyMap()
        section.getKeys(false)
            .associateWith { section.getString(it, "") ?: "" }
            .filterValues(::isDiscordWebhookUrl)
    }

    fun isConfigured(channel: String): Boolean = webhooks.containsKey(channel)

    fun channels(): Set<String> = webhooks.keys

    /** Fire-and-forget compatibility API. */
    fun send(channel: String, content: String, allowMentions: Boolean = false) {
        sendAsync(channel, content, allowMentions)
    }

    /** Completes with true only when Discord accepted the message (2xx). */
    fun sendAsync(channel: String, content: String, allowMentions: Boolean = false): CompletableFuture<Boolean> {
        val url = webhook(channel) ?: return CompletableFuture.completedFuture(false)
        val payload = "{\"content\":${jsonString(content.take(MAX_CONTENT))}${if (allowMentions) "" else NO_MENTIONS}}"
        return post(channel, url, payload)
    }

    fun sendEmbed(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = "",
        allowMentions: Boolean = false,
        fields: List<KSLDiscordEmbedField> = emptyList()
    ) {
        sendEmbedAsync(channel, title, description, color, footer, allowMentions, fields)
    }

    /** Sends an embed and reports whether Discord accepted it. Invalid/extra fields are safely truncated. */
    fun sendEmbedAsync(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = "",
        allowMentions: Boolean = false,
        fields: List<KSLDiscordEmbedField> = emptyList()
    ): CompletableFuture<Boolean> {
        val url = webhook(channel) ?: return CompletableFuture.completedFuture(false)
        val footerBlock = if (footer.isNotBlank()) ",\"footer\":{\"text\":${jsonString(footer.take(MAX_FOOTER))}}" else ""
        val fieldBlock = fields.take(MAX_FIELDS).takeIf { it.isNotEmpty() }?.joinToString(
            prefix = ",\"fields\":[", postfix = "]"
        ) { field ->
            "{\"name\":${jsonString(field.name.take(256))},\"value\":${jsonString(field.value.take(1024))},\"inline\":${field.inline}}"
        } ?: ""
        val safeColor = color.coerceIn(0, 0xFFFFFF)
        val embed = "{\"title\":${jsonString(title.take(MAX_TITLE))},\"description\":${jsonString(description.take(MAX_DESCRIPTION))}," +
            "\"color\":$safeColor$footerBlock$fieldBlock}"
        val payload = "{\"embeds\":[$embed]${if (allowMentions) "" else NO_MENTIONS}}"
        return post(channel, url, payload)
    }

    private fun webhook(channel: String): String? = webhooks[channel] ?: run {
        plugin.logger.warning("[Discord] ${KSLError.DISCORD_NOT_CONFIGURED.tag} Канал '$channel' не настроен в config.yml")
        null
    }

    // Webhook URLs contain secret tokens: never include URLs or request bodies in logs.
    // Retries are chained asynchronously: never block HttpClient's completion thread with join().
    private fun post(channel: String, url: String, json: String, attempt: Int = 0): CompletableFuture<Boolean> {
        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("User-Agent", "KotlinScriptLoader/${plugin.description.version}")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build()
        } catch (ex: Exception) {
            plugin.logger.warning("[Discord] ${KSLError.DISCORD_SEND_FAILED.tag} Некорректный webhook для канала '$channel': ${ex.javaClass.simpleName}")
            return CompletableFuture.completedFuture(false)
        }

        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle { response, ex ->
            when {
                ex != null && attempt < MAX_RETRIES -> retry(channel, url, json, attempt, backoffMillis(attempt))
                ex != null -> {
                    plugin.logger.warning("[Discord] ${KSLError.DISCORD_SEND_FAILED.tag} Ошибка отправки в канал '$channel': ${ex.javaClass.simpleName}")
                    CompletableFuture.completedFuture(false)
                }
                response.statusCode() == 429 && attempt < MAX_RETRIES -> {
                    val seconds = response.headers().firstValue("retry-after").orElse("1").toDoubleOrNull() ?: 1.0
                    val delayMs = (seconds * 1000).toLong().coerceIn(100L, 10_000L)
                    retry(channel, url, json, attempt, delayMs)
                }
                response.statusCode() in 500..599 && attempt < MAX_RETRIES ->
                    retry(channel, url, json, attempt, backoffMillis(attempt))
                response.statusCode() in 200..299 -> CompletableFuture.completedFuture(true)
                else -> {
                    plugin.logger.warning("[Discord] ${KSLError.DISCORD_HTTP_ERROR.tag} HTTP ${response.statusCode()} при отправке в канал '$channel'")
                    CompletableFuture.completedFuture(false)
                }
            }
        }.thenCompose { it }
    }

    private fun backoffMillis(attempt: Int): Long = (500L shl attempt.coerceIn(0, 4)).coerceAtMost(5_000L)

    private fun retry(
        channel: String,
        url: String,
        json: String,
        attempt: Int,
        delayMs: Long
    ): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync(
            { post(channel, url, json, attempt + 1) },
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
        ).thenCompose { it }
}
