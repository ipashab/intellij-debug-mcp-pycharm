package com.sahil0.debugmcp.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {

    private lateinit var server: McpServer

    @BeforeEach
    fun setup() {
        server = McpServer(serverName = "test-server", serverVersion = "0.0.1")
    }

    // ── Helper ──

    private fun jsonRpc(method: String, id: Int? = 1, params: JsonObject? = null): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
    }

    // ══════════════════════════════════════════════════
    //  initialize
    // ══════════════════════════════════════════════════

    @Nested
    inner class Initialize {

        @Test
        fun `returns protocol version and server info`() = runTest {
            val resp = server.handleMessage(jsonRpc("initialize", id = 1, params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "unit-test")
                    put("version", "1.0")
                })
            }))

            assertNotNull(resp)
            assertEquals("2.0", resp["jsonrpc"]!!.jsonPrimitive.content)
            assertEquals(1, resp["id"]!!.jsonPrimitive.int)

            val result = resp["result"]!!.jsonObject
            assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)

            val serverInfo = result["serverInfo"]!!.jsonObject
            assertEquals("test-server", serverInfo["name"]!!.jsonPrimitive.content)
            assertEquals("0.0.1", serverInfo["version"]!!.jsonPrimitive.content)
        }

        @Test
        fun `returns tools capability`() = runTest {
            val resp = server.handleMessage(jsonRpc("initialize"))
            val caps = resp!!["result"]!!.jsonObject["capabilities"]!!.jsonObject
            assertNotNull(caps["tools"])
            assertTrue(caps["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.boolean)
        }

        @Test
        fun `preserves request id in response`() = runTest {
            val resp = server.handleMessage(jsonRpc("initialize", id = 42))
            assertEquals(42, resp!!["id"]!!.jsonPrimitive.int)
        }
    }

    // ══════════════════════════════════════════════════
    //  notifications/initialized
    // ══════════════════════════════════════════════════

    @Nested
    inner class NotificationsInitialized {

        @Test
        fun `returns null (no response for notifications)`() = runTest {
            val resp = server.handleMessage(jsonRpc("notifications/initialized", id = null))
            assertNull(resp)
        }
    }

    // ══════════════════════════════════════════════════
    //  ping
    // ══════════════════════════════════════════════════

    @Nested
    inner class Ping {

        @Test
        fun `returns empty result`() = runTest {
            val resp = server.handleMessage(jsonRpc("ping", id = 5))
            assertNotNull(resp)
            assertEquals(5, resp["id"]!!.jsonPrimitive.int)
            val result = resp["result"]!!.jsonObject
            assertTrue(result.isEmpty())
        }
    }

    // ══════════════════════════════════════════════════
    //  tools/list
    // ══════════════════════════════════════════════════

    @Nested
    inner class ToolsList {

        @Test
        fun `returns empty list when no tools registered`() = runTest {
            val resp = server.handleMessage(jsonRpc("tools/list"))
            val tools = resp!!["result"]!!.jsonObject["tools"]!!.jsonArray
            assertTrue(tools.isEmpty())
        }

        @Test
        fun `returns registered tools`() = runTest {
            server.addTool(name = "alpha", description = "Alpha tool") {
                CallToolResult(content = listOf(TextContent(text = "ok")))
            }
            server.addTool(
                name = "beta",
                description = "Beta tool",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("x", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("x")
                )
            ) {
                CallToolResult(content = listOf(TextContent(text = "ok")))
            }

            val resp = server.handleMessage(jsonRpc("tools/list"))
            val tools = resp!!["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(2, tools.size)

            val alpha = tools[0].jsonObject
            assertEquals("alpha", alpha["name"]!!.jsonPrimitive.content)
            assertEquals("Alpha tool", alpha["description"]!!.jsonPrimitive.content)
            // inputSchema present even with no explicit schema
            assertNotNull(alpha["inputSchema"])
            assertEquals("object", alpha["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            val beta = tools[1].jsonObject
            assertEquals("beta", beta["name"]!!.jsonPrimitive.content)
            val betaSchema = beta["inputSchema"]!!.jsonObject
            assertNotNull(betaSchema["properties"]!!.jsonObject["x"])
            assertEquals(listOf("x"), betaSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

        @Test
        fun `tool without required omits required array`() = runTest {
            server.addTool(
                name = "no_req",
                description = "No required",
                inputSchema = ToolSchema(
                    properties = buildJsonObject { put("opt", buildJsonObject { put("type", "string") }) },
                    required = emptyList()
                )
            ) {
                CallToolResult(content = listOf(TextContent(text = "ok")))
            }

            val resp = server.handleMessage(jsonRpc("tools/list"))
            val tool = resp!!["result"]!!.jsonObject["tools"]!!.jsonArray[0].jsonObject
            val schema = tool["inputSchema"]!!.jsonObject
            assertNull(schema["required"], "Empty required should not appear in schema")
        }
    }

    // ══════════════════════════════════════════════════
    //  tools/call — success
    // ══════════════════════════════════════════════════

    @Nested
    inner class ToolsCallSuccess {

        @Test
        fun `calls tool and returns text content`() = runTest {
            server.addTool(name = "echo", description = "Echoes input") { req ->
                val input = req.arguments?.get("msg")?.jsonPrimitive?.content ?: "no input"
                CallToolResult(content = listOf(TextContent(text = input)))
            }

            val resp = server.handleMessage(jsonRpc("tools/call", id = 10, params = buildJsonObject {
                put("name", "echo")
                put("arguments", buildJsonObject { put("msg", "hello") })
            }))

            assertNotNull(resp)
            assertEquals(10, resp["id"]!!.jsonPrimitive.int)
            val result = resp["result"]!!.jsonObject
            val content = result["content"]!!.jsonArray
            assertEquals(1, content.size)
            assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("hello", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        }

        @Test
        fun `calls tool with null arguments`() = runTest {
            server.addTool(name = "no_args", description = "No args tool") {
                CallToolResult(content = listOf(TextContent(text = "done")))
            }

            val resp = server.handleMessage(jsonRpc("tools/call", id = 11, params = buildJsonObject {
                put("name", "no_args")
            }))

            val text = resp!!["result"]!!.jsonObject["content"]!!.jsonArray[0]
                .jsonObject["text"]!!.jsonPrimitive.content
            assertEquals("done", text)
        }

        @Test
        fun `isError flag is propagated when tool sets it`() = runTest {
            server.addTool(name = "fail_soft", description = "Returns error result") {
                CallToolResult(content = listOf(TextContent(text = "oops")), isError = true)
            }

            val resp = server.handleMessage(jsonRpc("tools/call", id = 12, params = buildJsonObject {
                put("name", "fail_soft")
            }))

            val result = resp!!["result"]!!.jsonObject
            assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        }

        @Test
        fun `isError not present when tool succeeds`() = runTest {
            server.addTool(name = "ok", description = "OK") {
                CallToolResult(content = listOf(TextContent(text = "fine")))
            }

            val resp = server.handleMessage(jsonRpc("tools/call", id = 13, params = buildJsonObject {
                put("name", "ok")
            }))

            val result = resp!!["result"]!!.jsonObject
            assertNull(result["isError"], "isError should not be present on success")
        }

        @Test
        fun `multiple content items returned`() = runTest {
            server.addTool(name = "multi", description = "Multi content") {
                CallToolResult(content = listOf(
                    TextContent(text = "line1"),
                    TextContent(text = "line2"),
                    TextContent(text = "line3")
                ))
            }

            val resp = server.handleMessage(jsonRpc("tools/call", id = 14, params = buildJsonObject {
                put("name", "multi")
            }))

            val content = resp!!["result"]!!.jsonObject["content"]!!.jsonArray
            assertEquals(3, content.size)
            assertEquals("line1", content[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("line3", content[2].jsonObject["text"]!!.jsonPrimitive.content)
        }
    }

    // ══════════════════════════════════════════════════
    //  tools/call — errors
    // ══════════════════════════════════════════════════

    @Nested
    inner class ToolsCallErrors {

        @Test
        fun `unknown tool returns error content`() = runTest {
            val resp = server.handleMessage(jsonRpc("tools/call", id = 20, params = buildJsonObject {
                put("name", "nonexistent")
            }))

            val result = resp!!["result"]!!.jsonObject
            assertTrue(result["isError"]!!.jsonPrimitive.boolean)
            val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.contains("nonexistent"), "Error should mention the tool name")
        }

        @Test
        fun `tool throwing exception returns error content`() = runTest {
            server.addTool(name = "crasher", description = "Throws") {
                throw RuntimeException("something broke")
            }

            val resp = server.handleMessage(jsonRpc("tools/call", id = 21, params = buildJsonObject {
                put("name", "crasher")
            }))

            val result = resp!!["result"]!!.jsonObject
            assertTrue(result["isError"]!!.jsonPrimitive.boolean)
            val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.contains("something broke"))
        }

        @Test
        fun `tool exception with quotes in message is properly escaped`() = runTest {
            server.addTool(name = "quoter", description = "Throws with quotes") {
                throw RuntimeException("""Value "foo" is invalid""")
            }

            val resp = server.handleMessage(jsonRpc("tools/call", id = 22, params = buildJsonObject {
                put("name", "quoter")
            }))

            // Should not throw — the JSON should be well-formed
            val result = resp!!["result"]!!.jsonObject
            assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        }

        @Test
        fun `missing tool name returns error`() = runTest {
            val resp = server.handleMessage(jsonRpc("tools/call", id = 23, params = buildJsonObject {}))

            val result = resp!!["result"]!!.jsonObject
            assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        }
    }

    // ══════════════════════════════════════════════════
    //  Unknown methods
    // ══════════════════════════════════════════════════

    @Nested
    inner class UnknownMethod {

        @Test
        fun `unknown method with id returns -32601 error`() = runTest {
            val resp = server.handleMessage(jsonRpc("some/unknown", id = 30))
            assertNotNull(resp)
            val error = resp["error"]!!.jsonObject
            assertEquals(-32601, error["code"]!!.jsonPrimitive.int)
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("some/unknown"))
        }

        @Test
        fun `unknown notification (no id) returns null`() = runTest {
            val resp = server.handleMessage(jsonRpc("some/notification", id = null))
            assertNull(resp)
        }
    }

    // ══════════════════════════════════════════════════
    //  handleMessage edge cases
    // ══════════════════════════════════════════════════

    @Nested
    inner class EdgeCases {

        @Test
        fun `message without method returns null`() = runTest {
            val resp = server.handleMessage(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
            })
            assertNull(resp)
        }

        @Test
        fun `addTool replaces existing tool with same name`() = runTest {
            server.addTool(name = "dup", description = "First") {
                CallToolResult(content = listOf(TextContent(text = "first")))
            }
            server.addTool(name = "dup", description = "Second") {
                CallToolResult(content = listOf(TextContent(text = "second")))
            }

            // tools/list should show only one "dup"
            val listResp = server.handleMessage(jsonRpc("tools/list"))
            val tools = listResp!!["result"]!!.jsonObject["tools"]!!.jsonArray
            val dupTools = tools.filter { it.jsonObject["name"]!!.jsonPrimitive.content == "dup" }
            assertEquals(1, dupTools.size)
            assertEquals("Second", dupTools[0].jsonObject["description"]!!.jsonPrimitive.content)

            // tools/call should use the second handler
            val callResp = server.handleMessage(jsonRpc("tools/call", params = buildJsonObject {
                put("name", "dup")
            }))
            val text = callResp!!["result"]!!.jsonObject["content"]!!.jsonArray[0]
                .jsonObject["text"]!!.jsonPrimitive.content
            assertEquals("second", text)
        }

        @Test
        fun `string id is preserved in response`() = runTest {
            val msg = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "abc-123")
                put("method", "ping")
            }
            val resp = server.handleMessage(msg)
            assertEquals("abc-123", resp!!["id"]!!.jsonPrimitive.content)
        }
    }
}
