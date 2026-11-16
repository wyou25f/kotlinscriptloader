package ru.privateserver.ksl.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLMcpProtocolTest {
    private class FakeOperations : KSLMcpOperations {
        override fun serverStatus() = buildJsonObject { put("onlinePlayers", 3) }
        override fun listScripts(limit: Int) = buildJsonObject {
            put("limit", limit)
            put("returned", 0)
        }
        override fun reloadScript(scriptId: String) = buildJsonObject {
            put("scriptId", scriptId)
            put("status", "reloaded")
        }
    }

    private val protocol = KSLMcpProtocol(FakeOperations(), "test", allowScriptReload = false)

    private fun request(method: String, params: JsonObject = buildJsonObject {}, id: Int = 1) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", buildJsonObject {
                params.forEach { (key, value) -> put(key, value) }
                if (!params.containsKey("_meta")) {
                    put("_meta", buildJsonObject {
                        put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                        put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {})
                    })
                }
            })
        }

    @Test
    fun toolsListPublishesOnlyReadOnlyToolsWhenReloadIsDisabled() {
        val reply = protocol.process(
            request("tools/list"),
            KSLMcpRequestHeaders("2026-07-28", "tools/list", null)
        )
        assertEquals(200, reply.httpStatus)
        val tools = reply.body!!.getValue("result").jsonObject.getValue("tools").jsonArray
        assertEquals(2, tools.size)
        assertTrue(tools.toString().contains("ksl_server_status"))
        assertTrue(tools.toString().contains("ksl_list_scripts"))
        assertFalse(tools.toString().contains("ksl_reload_script"))
        assertTrue(reply.body.toString().contains("io.modelcontextprotocol/serverInfo"))
    }

    @Test
    fun serverStatusToolReturnsStructuredJsonAsText() {
        val params = buildJsonObject {
            put("name", "ksl_server_status")
        }
        val reply = protocol.process(
            request("tools/call", params),
            KSLMcpRequestHeaders("2026-07-28", "tools/call", "ksl_server_status")
        )
        assertEquals(200, reply.httpStatus)
        val result = reply.body!!.getValue("result").jsonObject
        assertTrue(result.getValue("content").toString().contains("onlinePlayers"))
        assertEquals("false", result.getValue("isError").jsonPrimitive.content)
    }

    @Test
    fun mcpMethodHeaderMustMatchRequestBody() {
        val reply = protocol.process(
            request("tools/list"),
            KSLMcpRequestHeaders("2026-07-28", "tools/call", null)
        )
        assertEquals(400, reply.httpStatus)
        assertTrue(reply.body.toString().contains("-32020"))
    }

    @Test
    fun latestProtocolMetadataIsMandatory() {
        val invalid = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/list")
            put("params", buildJsonObject {})
        }
        val reply = protocol.process(
            invalid,
            KSLMcpRequestHeaders("2026-07-28", "tools/list", null)
        )
        assertEquals(400, reply.httpStatus)
        assertTrue(reply.body.toString().contains("-32602"))
    }

    @Test
    fun toolCallNameHeaderMustMatchToolName() {
        val params = buildJsonObject {
            put("name", "ksl_server_status")
            put("arguments", buildJsonObject {})
        }
        val reply = protocol.process(
            request("tools/call", params),
            KSLMcpRequestHeaders("2026-07-28", "tools/call", "different_tool")
        )
        assertEquals(400, reply.httpStatus)
        assertTrue(reply.body.toString().contains("-32020"))
    }

    @Test
    fun unsupportedToolIsReturnedAsMcpToolError() {
        val params = buildJsonObject {
            put("name", "unknown")
            put("arguments", buildJsonObject {})
        }
        val reply = protocol.process(
            request("tools/call", params),
            KSLMcpRequestHeaders("2026-07-28", "tools/call", "unknown")
        )
        assertEquals(200, reply.httpStatus)
        assertTrue(reply.body.toString().contains("\"isError\":true"))
    }
    @Test
    fun notificationsAreAcceptedWithoutJsonRpcResponse() {
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/cancelled")
            put("params", buildJsonObject {
                put("_meta", buildJsonObject {
                    put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                    put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {})
                })
            })
        }
        val reply = protocol.process(
            notification,
            KSLMcpRequestHeaders("2026-07-28", "notifications/cancelled", null)
        )
        assertEquals(202, reply.httpStatus)
        assertEquals(null, reply.body)
    }

    @Test
    fun stringValuesAreNotAcceptedForIntegerToolArguments() {
        val params = buildJsonObject {
            put("name", "ksl_list_scripts")
            put("arguments", buildJsonObject { put("limit", "10") })
        }
        val reply = protocol.process(
            request("tools/call", params),
            KSLMcpRequestHeaders("2026-07-28", "tools/call", "ksl_list_scripts")
        )
        assertEquals(200, reply.httpStatus)
        val result = reply.body!!.getValue("result").jsonObject
        assertEquals("true", result.getValue("isError").jsonPrimitive.content)
    }

    @Test
    fun parseErrorUsesNullJsonRpcId() {
        val reply = protocol.parseError()
        assertEquals(400, reply.httpStatus)
        assertTrue(reply.body.toString().contains("\"id\":null"))
    }

    @Test
    fun internalOperationExceptionsDoNotLeakExceptionDetails() {
        val broken = object : KSLMcpOperations {
            override fun serverStatus(): JsonObject {
                throw IllegalArgumentException("/srv/private/server/config.yml")
            }
            override fun listScripts(limit: Int): JsonObject = buildJsonObject {}
            override fun reloadScript(scriptId: String): JsonObject = buildJsonObject {}
        }
        val isolatedProtocol = KSLMcpProtocol(broken, "test", allowScriptReload = false)
        val params = buildJsonObject {
            put("name", "ksl_server_status")
        }
        val reply = isolatedProtocol.process(
            request("tools/call", params),
            KSLMcpRequestHeaders("2026-07-28", "tools/call", "ksl_server_status")
        )
        assertEquals(200, reply.httpStatus)
        assertTrue(reply.body.toString().contains("Check the server console"))
        assertFalse(reply.body.toString().contains("/srv/private"))
    }

}
