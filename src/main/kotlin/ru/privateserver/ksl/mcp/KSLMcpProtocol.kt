package ru.privateserver.ksl.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal interface KSLMcpOperations {
    fun serverStatus(): JsonObject
    fun listScripts(limit: Int): JsonObject
    fun reloadScript(scriptId: String): JsonObject
}

internal data class KSLMcpRequestHeaders(
    val protocolVersion: String?,
    val method: String?,
    val name: String?
)

internal data class KSLMcpProtocolReply(
    val httpStatus: Int,
    val body: JsonObject?
)

internal class KSLMcpProtocol(
    private val operations: KSLMcpOperations,
    private val serverVersion: String,
    private val allowScriptReload: Boolean,
    private val onInternalError: (Throwable) -> Unit = {}
) {
    private val json = Json { prettyPrint = false }

    fun process(body: JsonElement, headers: KSLMcpRequestHeaders): KSLMcpProtocolReply {
        var requestId: JsonElement? = null
        return try {
            val request = body as? JsonObject ?: throw RpcFault(400, -32600, "Invalid JSON-RPC request")
            requestId = request["id"]
            if (stringValue(request["jsonrpc"]) != "2.0") {
                throw RpcFault(400, -32600, "jsonrpc must be 2.0")
            }

            val method = stringValue(request["method"])
                ?.takeIf { it.isNotBlank() }
                ?: throw RpcFault(400, -32600, "Missing method")
            validateHeaders(method, request, headers)

            val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
            validateRequestMetadata(params)

            val id = requestId
            if (id == null) {
                if (!method.startsWith("notifications/")) {
                    throw RpcFault(400, -32600, "A JSON-RPC request requires an id")
                }
                return KSLMcpProtocolReply(202, null)
            }
            if (id == JsonNull || !isValidId(id)) {
                throw RpcFault(400, -32600, "Request id must be a string or integer")
            }

            val result = when (method) {
                "server/discover" -> discoverResult()
                "ping" -> buildJsonObject { put("resultType", "complete") }
                "tools/list" -> toolsListResult()
                "tools/call" -> toolsCallResult(params)
                else -> throw RpcFault(404, -32601, "Method not found")
            }

            KSLMcpProtocolReply(
                200,
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", withServerMetadata(result))
                }
            )
        } catch (fault: RpcFault) {
            KSLMcpProtocolReply(fault.httpStatus, rpcError(requestId, fault.code, fault.message ?: "Request failed"))
        } catch (_: Exception) {
            KSLMcpProtocolReply(500, rpcError(requestId, -32603, "Internal KSL MCP error"))
        }
    }

    fun parseError(): KSLMcpProtocolReply =
        KSLMcpProtocolReply(400, rpcError(null, -32700, "Parse error"))

    private fun validateHeaders(method: String, request: JsonObject, headers: KSLMcpRequestHeaders) {
        if (headers.protocolVersion != PROTOCOL_VERSION) {
            throw RpcFault(400, -32022, "Unsupported MCP protocol version; supported: $PROTOCOL_VERSION")
        }
        if (headers.method != method) {
            throw RpcFault(400, -32020, "Mcp-Method header does not match the JSON-RPC method")
        }

        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val parameterName = if (method == "tools/call") {
            stringValue(params["name"])
                ?: throw RpcFault(400, -32602, "tools/call requires a string params.name")
        } else {
            null
        }
        if (parameterName != headers.name) {
            throw RpcFault(400, -32020, "Mcp-Name header does not match params.name")
        }
    }

    private fun validateRequestMetadata(params: JsonObject) {
        val metadata = params["_meta"] as? JsonObject
            ?: throw RpcFault(400, -32602, "Missing per-request MCP metadata")
        val protocolVersion = stringValue(metadata["io.modelcontextprotocol/protocolVersion"])
        if (protocolVersion != PROTOCOL_VERSION) {
            throw RpcFault(400, -32602, "Request metadata has an unsupported protocol version")
        }
        if (metadata["io.modelcontextprotocol/clientCapabilities"] !is JsonObject) {
            throw RpcFault(400, -32602, "clientCapabilities must be an object")
        }
    }

    private fun discoverResult(): JsonObject = buildJsonObject {
        put("resultType", "complete")
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject { put("listChanged", false) })
        })
        put("ttlMs", 5_000)
        put("cacheScope", "private")
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put("resultType", "complete")
        put("ttlMs", 5_000)
        put("cacheScope", "private")
        put("tools", buildJsonArray {
            add(toolDefinition(
                name = "ksl_server_status",
                description = "Read-only overview of the Minecraft server and KSL runtime. Does not expose player identities, files, secrets, or console output.",
                schema = emptyObjectSchema(),
                readOnly = true
            ))
            add(toolDefinition(
                name = "ksl_list_scripts",
                description = "List script IDs and basic load status. Script source code and file paths are never returned.",
                schema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 50)
                            put("description", "Maximum number of scripts to return (default 50).")
                        })
                    })
                    put("additionalProperties", false)
                },
                readOnly = true
            ))
            if (allowScriptReload) {
                add(toolDefinition(
                    name = "ksl_reload_script",
                    description = "State-changing operation: reload exactly one named KSL script. Runs that script inside the Paper server JVM. Requires confirm=true and is disabled unless an operator explicitly enables mcp.allow-script-reload in config.yml. Never use for untrusted scripts.",
                    schema = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("script_id", buildJsonObject {
                                put("type", "string")
                                put("minLength", 1)
                                put("maxLength", 128)
                                put("pattern", "^[A-Za-z0-9_-][A-Za-z0-9._-]*(?:/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$")
                                put("description", "Safe relative script ID as shown by ksl_list_scripts, without the .kts suffix.")
                            })
                            put("confirm", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Must be true to acknowledge that reloading executes the selected script on the server.")
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("script_id"))
                            add(JsonPrimitive("confirm"))
                        })
                        put("additionalProperties", false)
                    },
                    readOnly = false
                ))
            }
        })
    }

    private fun toolsCallResult(params: JsonObject): JsonObject {
        val name = stringValue(params["name"])
            ?: throw RpcFault(400, -32602, "Missing string tool name")
        val arguments = when (val rawArguments = params["arguments"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> rawArguments
            else -> throw RpcFault(400, -32602, "Tool arguments must be an object")
        }

        return try {
            when (name) {
                "ksl_server_status" -> {
                    requireNoArguments(arguments)
                    safeOperation { operations.serverStatus() }
                }
                "ksl_list_scripts" -> {
                    require(arguments.keys.all { it == "limit" }) { "Unsupported arguments" }
                    val rawLimit = arguments["limit"]
                    val limit = if (rawLimit == null) 50 else {
                        val primitive = rawLimit as? JsonPrimitive
                            ?: throw IllegalArgumentException("limit must be an integer")
                        require(!primitive.isString) { "limit must be an integer" }
                        primitive.intOrNull ?: throw IllegalArgumentException("limit must be an integer")
                    }
                    require(limit in 1..50) { "limit must be between 1 and 50" }
                    safeOperation { operations.listScripts(limit) }
                }
                "ksl_reload_script" -> {
                    require(allowScriptReload) { "Script reload is disabled by the server operator" }
                    require(arguments.keys == setOf("script_id", "confirm")) {
                        "script_id and confirm are required"
                    }
                    val scriptId = stringValue(arguments["script_id"])
                        ?: throw IllegalArgumentException("script_id must be a string")
                    require(scriptId.length <= 128 && SCRIPT_ID.matches(scriptId)) { "Invalid script_id" }
                    val confirmation = arguments["confirm"] as? JsonPrimitive
                    require(confirmation != null && !confirmation.isString && confirmation.booleanOrNull == true) {
                        "Set confirm=true to acknowledge this operation"
                    }
                    safeOperation { operations.reloadScript(scriptId) }
                }
                else -> toolResult("Unknown or disabled tool", isError = true)
            }
        } catch (ex: IllegalArgumentException) {
            toolResult("Request rejected: ${ex.message ?: "invalid arguments"}", isError = true)
        } catch (_: Exception) {
            toolResult("KSL could not validate this MCP request. Check the server console for details.", isError = true)
        }
    }

    private fun safeOperation(operation: () -> JsonObject): JsonObject {
        val payload = try {
            operation()
        } catch (error: Exception) {
            runCatching { onInternalError(error) }
            return toolResult("KSL could not complete this operation. Check the server console for details.", isError = true)
        }
        return try {
            toolResult(json.encodeToString(JsonElement.serializer(), payload), isError = false)
        } catch (_: Exception) {
            toolResult("KSL could not format the operation result.", isError = true)
        }
    }

    private fun requireNoArguments(arguments: JsonObject) {
        require(arguments.isEmpty()) { "This tool does not accept arguments" }
    }

    private fun toolResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("resultType", "complete")
        put("isError", isError)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text.take(MAX_TOOL_TEXT))
            })
        })
    }

    private fun toolDefinition(
        name: String,
        description: String,
        schema: JsonObject,
        readOnly: Boolean
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", schema)
        put("annotations", buildJsonObject {
            put("readOnlyHint", readOnly)
            put("destructiveHint", !readOnly)
            put("idempotentHint", readOnly)
            put("openWorldHint", false)
        })
    }

    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("additionalProperties", false)
    }

    private fun withServerMetadata(result: JsonObject): JsonObject = buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        put("_meta", buildJsonObject {
            put("io.modelcontextprotocol/serverInfo", buildJsonObject {
                put("name", "KotlinScriptLoader")
                put("version", serverVersion)
            })
        })
    }

    private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message.take(200))
        })
    }

    private fun stringValue(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isValidId(id: JsonElement): Boolean {
        val primitive = id as? JsonPrimitive ?: return false
        if (primitive.isString) return primitive.content.isNotEmpty() && primitive.content.length <= 128
        return primitive.content.toLongOrNull() != null
    }

    private class RpcFault(
        val httpStatus: Int,
        val code: Int,
        message: String
    ) : RuntimeException(message)

    private companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        const val MAX_TOOL_TEXT = 12_000
        val SCRIPT_ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*(?:/[A-Za-z0-9_-][A-Za-z0-9._-]*)*")
    }
}
