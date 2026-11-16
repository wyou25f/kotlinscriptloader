package ru.privateserver.ksl.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.bukkit.Bukkit
import ru.privateserver.ksl.KotlinScriptLoaderPlugin
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

private val MCP_SCRIPT_ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*(?:/[A-Za-z0-9_-][A-Za-z0-9._-]*)*")

internal object KSLMcpSecurity {
    private val bearerTokenPattern = Regex("[A-Za-z0-9._~-]{32,256}")

    fun isLoopbackBind(host: String): Boolean = host == "127.0.0.1" || host == "::1"

    fun validateOrigins(origins: List<String>): Set<String> {
        val validated = LinkedHashSet<String>()
        origins.forEach { origin ->
            require(origin != "*") { "Wildcard MCP origins are not allowed" }
            val uri = runCatching { URI(origin) }.getOrElse {
                throw IllegalArgumentException("Invalid MCP allowed origin")
            }
            require(
                uri.isAbsolute &&
                    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawPath.isNullOrEmpty() &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    origin == "${uri.scheme}://${uri.rawAuthority}"
            ) { "Allowed MCP origins must be exact http(s) origins without paths, queries or fragments" }
            validated += origin
        }
        return validated
    }

    fun originAllowed(origin: String?, allowedOrigins: Set<String>): Boolean =
        origin == null || origin in allowedOrigins

    fun validHostHeader(header: String?, port: Int): Boolean {
        if (port !in 1..65535 || header == null) return false
        val allowed = setOf(
            "127.0.0.1:$port",
            "localhost:$port",
            "[::1]:$port"
        )
        return header.lowercase(java.util.Locale.ROOT) in allowed
    }

    fun acceptsMcpResponse(header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        val accepted = header.split(',').mapNotNull { part ->
            val segments = part.split(';').map(String::trim)
            val mediaType = segments.firstOrNull()?.lowercase(java.util.Locale.ROOT)
                ?: return@mapNotNull null
            val qualityParameter = segments.drop(1).firstOrNull {
                it.startsWith("q=", ignoreCase = true)
            }
            val quality = qualityParameter?.substringAfter('=')?.toDoubleOrNull() ?: if (qualityParameter == null) 1.0 else 0.0
            mediaType.takeIf { quality.isFinite() && quality in 0.0..1.0 && quality > 0.0 }
        }.toSet()
        return "application/json" in accepted && "text/event-stream" in accepted
    }

    fun bearerTokenMatches(header: String?, expectedToken: String): Boolean {
        if (header == null || !header.startsWith("Bearer ")) return false
        val supplied = header.removePrefix("Bearer ").toByteArray(StandardCharsets.UTF_8)
        val expected = expectedToken.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(expected, supplied)
    }

    fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes in 1..1_048_576) { "maxBytes is outside the supported range" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 4096))
        val buffer = ByteArray(4096)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) {
                    throw IllegalArgumentException("Request body is too large")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    fun validToken(token: String?): Boolean =
        token != null && bearerTokenPattern.matches(token)
}

internal class KSLMcpServer private constructor(
    private val plugin: KotlinScriptLoaderPlugin,
    private val bindHost: String,
    private val port: Int,
    private val endpointPath: String,
    private val token: String,
    private val allowedOrigins: Set<String>,
    private val allowScriptReload: Boolean
) : AutoCloseable, KSLMcpOperations {

    private val json = Json { ignoreUnknownKeys = false }
    private val protocol = KSLMcpProtocol(this, plugin.description.version, allowScriptReload) { error ->
        plugin.logger.log(Level.WARNING, "[MCP] Internal tool operation failed", error)
    }
    private val executor = ThreadPoolExecutor(
        2,
        4,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        daemonFactory("KSL-MCP"),
        ThreadPoolExecutor.AbortPolicy()
    )
    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getByName(bindHost), port),
        16
    ).apply {
        this.executor = this@KSLMcpServer.executor
        createContext(endpointPath) { exchange -> handle(exchange) }
    }

    private val displayHost = if (':' in bindHost) "[$bindHost]" else bindHost

    @Volatile
    private var started = false

    fun start() {
        check(!started) { "MCP server already started" }
        server.start()
        started = true
        plugin.logger.info("[MCP] Local endpoint started at http://$displayHost:$port$endpointPath")
        if (allowScriptReload) {
            plugin.logger.warning("[MCP] Script reload tool is enabled. MCP clients can trigger execution of individual .kts scripts.")
        }
    }

    fun statusText(): String =
        if (started) "enabled at http://$displayHost:$port$endpointPath (loopback only)"
        else "stopped"

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestURI.rawPath != endpointPath || exchange.requestURI.rawQuery != null) {
                respond(exchange, 404, null)
                return
            }
            if (!exchange.remoteAddress.address.isLoopbackAddress) {
                respond(exchange, 403, null)
                return
            }
            if (exchange.requestMethod != "POST") {
                exchange.responseHeaders.set("Allow", "POST")
                respond(exchange, 405, null)
                return
            }
            val singleValueHeaders = listOf(
                "Host", "Origin", "Authorization", "Accept", "Content-Type", "Content-Length",
                "MCP-Protocol-Version", "Mcp-Method", "Mcp-Name"
            )
            if (singleValueHeaders.any { exchange.requestHeaders[it]?.size?.let { count -> count > 1 } == true }) {
                respond(exchange, 400, null)
                return
            }
            if (!consumeRateLimit()) {
                respond(exchange, 429, null)
                return
            }
            if (!KSLMcpSecurity.originAllowed(
                    exchange.requestHeaders.getFirst("Origin"),
                    allowedOrigins
                )
            ) {
                respond(exchange, 403, null)
                return
            }
            if (!KSLMcpSecurity.validHostHeader(exchange.requestHeaders.getFirst("Host"), port)) {
                respond(exchange, 403, null)
                return
            }
            if (!KSLMcpSecurity.bearerTokenMatches(
                    exchange.requestHeaders.getFirst("Authorization"),
                    token
                )
            ) {
                respond(exchange, 401, null)
                return
            }
            if (!KSLMcpSecurity.acceptsMcpResponse(exchange.requestHeaders.getFirst("Accept"))) {
                respond(exchange, 406, null)
                return
            }
            val contentType = exchange.requestHeaders.getFirst("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(java.util.Locale.ROOT)
            if (contentType != "application/json") {
                respond(exchange, 415, null)
                return
            }
            val contentLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
            if (contentLength != null && contentLength !in 0..MAX_BODY_BYTES.toLong()) {
                respond(exchange, 413, null)
                return
            }

            val bytes = try {
                KSLMcpSecurity.readBounded(exchange.requestBody, MAX_BODY_BYTES)
            } catch (_: IllegalArgumentException) {
                respond(exchange, 413, null)
                return
            }
            val parsed = try {
                json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8))
            } catch (_: Exception) {
                val reply = protocol.parseError()
                respond(exchange, reply.httpStatus, reply.body)
                return
            }

            val headers = KSLMcpRequestHeaders(
                protocolVersion = exchange.requestHeaders.getFirst("MCP-Protocol-Version"),
                method = exchange.requestHeaders.getFirst("Mcp-Method"),
                name = exchange.requestHeaders.getFirst("Mcp-Name")
            )
            val reply = protocol.process(parsed, headers)
            reply.body?.let {
                val request = parsed as? JsonObject
                val method = (request?.get("method") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { value -> value.isString }?.content
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_./-]{1,64}")) } ?: "invalid"
                val params = request?.get("params") as? JsonObject
                val requestedTool = (params?.get("name") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { value -> value.isString }?.content
                val auditedTool = requestedTool?.takeIf {
                    it in setOf("ksl_server_status", "ksl_list_scripts", "ksl_reload_script")
                }
                val action = if (auditedTool == null) method else "$method/$auditedTool"
                plugin.logger.info("[MCP] Handled $action request from loopback client")
            }
            respond(exchange, reply.httpStatus, reply.body)
        } catch (ex: java.util.concurrent.RejectedExecutionException) {
            runCatching { respond(exchange, 503, null) }
        } catch (ex: Exception) {
            plugin.logger.log(Level.WARNING, "[MCP] Request handling failed; details omitted from client response", ex)
            runCatching { respond(exchange, 500, null) }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, payload: JsonObject?) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        if (payload == null) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        val bytes = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private val rateLock = Any()
    private var rateWindow = System.nanoTime() / TimeUnit.MINUTES.toNanos(1)
    private var rateCount = 0

    private fun consumeRateLimit(): Boolean = synchronized(rateLock) {
        val window = System.nanoTime() / TimeUnit.MINUTES.toNanos(1)
        if (window != rateWindow) {
            rateWindow = window
            rateCount = 0
        }
        if (rateCount >= REQUESTS_PER_MINUTE) {
            false
        } else {
            rateCount++
            true
        }
    }

    override fun serverStatus(): JsonObject {
        // Refresh script metadata on the MCP worker: the index may scan scripts/ and must not stall a server tick.
        val scripts = plugin.scriptInfos()
        val serverSnapshot = onServerThread {
            buildJsonObject {
                put("serverVersion", Bukkit.getVersion())
                put("bukkitVersion", Bukkit.getBukkitVersion())
                put("onlinePlayers", Bukkit.getOnlinePlayers().size)
                put("maxPlayers", plugin.server.maxPlayers)
            }
        }
        return buildJsonObject {
            put("plugin", "KotlinScriptLoader")
            put("kslVersion", plugin.description.version)
            serverSnapshot.forEach { (key, value) -> put(key, value) }
            put("scriptsLoaded", scripts.count { it.loaded })
            put("scriptsTotal", scripts.size)
        }
    }

    override fun listScripts(limit: Int): JsonObject {
        // scriptInfos() refreshes a filesystem index, so keep it away from Bukkit's primary thread.
        val scripts = plugin.scriptInfos()
        val visible = scripts.filter { it.id.length <= 128 && MCP_SCRIPT_ID.matches(it.id) }
        val selected = visible.take(limit)
        return buildJsonObject {
            put("count", scripts.size)
            put("returned", selected.size)
            put("omittedUnsupportedIds", scripts.size - visible.size)
            put("truncated", visible.size > limit)
            put("scripts", buildJsonArray {
                selected.forEach { script ->
                    add(buildJsonObject {
                        put("id", script.id)
                        put("loaded", script.loaded)
                        put("loadMillis", script.loadMillis)
                        put("errors", script.errors)
                    })
                }
            })
        }
    }

    override fun reloadScript(scriptId: String): JsonObject {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Boolean?>(null)
        plugin.reloadScriptAsync(scriptId) { outcome ->
            result.set(outcome)
            latch.countDown()
        }
        val completed = try {
            latch.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val outcome = result.get()
        return buildJsonObject {
            put("scriptId", scriptId)
            put(
                "status",
                when {
                    !completed -> "timeout"
                    outcome == true -> "reloaded"
                    outcome == false -> "failed"
                    else -> "busy"
                }
            )
        }
    }

    private fun <T> onServerThread(block: () -> T): T {
        if (Bukkit.isPrimaryThread()) return block()
        return Bukkit.getScheduler()
            .callSyncMethod(plugin, java.util.concurrent.Callable { block() })
            .get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun close() {
        if (started) {
            runCatching { server.stop(0) }
            started = false
        } else {
            runCatching { server.stop(0) }
        }
        executor.shutdownNow()
    }

    private fun daemonFactory(prefix: String): ThreadFactory {
        val counter = java.util.concurrent.atomic.AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    plugin.logger.warning("[MCP] Worker failed: ${error::class.simpleName}")
                }
            }
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 65_536
        private const val REQUESTS_PER_MINUTE = 120
        private const val MAIN_THREAD_TIMEOUT_SECONDS = 2L
        private const val RELOAD_TIMEOUT_SECONDS = 30L

        fun fromConfig(plugin: KotlinScriptLoaderPlugin): KSLMcpServer {
            val section = plugin.config.getConfigurationSection("mcp")
                ?: throw IllegalArgumentException("Missing mcp section in config.yml")
            val bindHost = section.getString("bind", "127.0.0.1")!!.trim()
            require(KSLMcpSecurity.isLoopbackBind(bindHost)) {
                "MCP is loopback-only. mcp.bind must be 127.0.0.1 or ::1"
            }
            val port = section.getInt("port", 8765)
            require(port in 1024..65535) { "mcp.port must be between 1024 and 65535" }
            val endpointPath = section.getString("path", "/mcp")!!.trim()
            require(
                endpointPath.matches(Regex("/[A-Za-z0-9_/-]{1,64}")) &&
                    !endpointPath.contains("..") &&
                    !endpointPath.contains("//") &&
                    !endpointPath.endsWith("/")
            ) { "mcp.path must be a simple absolute path such as /mcp" }

            val tokenEnv = section.getString("token-env", "KSL_MCP_TOKEN")!!.trim()
            require(tokenEnv.matches(Regex("[A-Z_][A-Z0-9_]{0,63}"))) {
                "mcp.token-env must be an environment variable name"
            }
            val token = System.getenv(tokenEnv)
            require(KSLMcpSecurity.validToken(token)) {
                "Environment variable $tokenEnv must contain a 32-256 character ASCII bearer token using only letters, digits, '.', '_', '~' or '-'"
            }
            val origins = KSLMcpSecurity.validateOrigins(section.getStringList("allowed-origins"))
            val allowReload = section.getBoolean("allow-script-reload", false)
            return KSLMcpServer(plugin, bindHost, port, endpointPath, token!!, origins, allowReload)
        }
    }
}
