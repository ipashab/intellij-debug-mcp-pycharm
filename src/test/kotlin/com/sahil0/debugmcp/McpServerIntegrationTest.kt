package com.sahil0.debugmcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test that connects to a running IntelliJ Debug MCP server over TCP.
 *
 * Run with: mvn verify -Dit.test=McpServerIntegrationTest
 * Requires IntelliJ running with the plugin enabled.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class McpServerIntegrationTest {

    companion object {
        private var port: Int = 0
        private lateinit var sock: Socket
        private var requestId = 0
        private var buf = ByteArray(0)

        @JvmStatic
        @BeforeAll
        fun setup() {
            port = discoverPort()
            assumeTrue(port > 0, "No IntelliJ Debug MCP server found (no .idea/debugmcp.port)")
            try {
                sock = Socket("127.0.0.1", port)
                sock.soTimeout = 10_000
            } catch (e: Exception) {
                assumeTrue(false, "Cannot connect to IntelliJ on port $port: ${e.message}")
            }
        }

        private fun discoverPort(): Int {
            // Env override
            System.getenv("DEBUGMCP_PORT")?.trim()?.toIntOrNull()?.let { return it }

            // Walk up from CWD
            var d = File(System.getProperty("user.dir")).canonicalFile
            while (true) {
                val pf = File(d, ".idea/debugmcp.port")
                if (pf.isFile) {
                    return pf.readText().trim().toIntOrNull() ?: 0
                }
                val parent = d.parentFile ?: break
                if (parent == d) break
                d = parent
            }
            return 0
        }

        fun nextId(): Int = ++requestId

        fun sendJsonRpc(method: String, params: JsonObject? = null): JsonObject {
            val msg = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(nextId()))
                put("method", JsonPrimitive(method))
                if (params != null) put("params", params)
            }
            val payload = Json.encodeToString(msg) + "\n"
            sock.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
            return readResponse()
        }

        fun sendNotification(method: String, params: JsonObject? = null) {
            val msg = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive(method))
                if (params != null) put("params", params)
            }
            val payload = Json.encodeToString(msg) + "\n"
            sock.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
        }

        fun readResponse(): JsonObject {
            while (true) {
                val nlIdx = buf.indexOf('\n'.code.toByte())
                if (nlIdx >= 0) {
                    val line = buf.copyOfRange(0, nlIdx).toString(Charsets.UTF_8).trim()
                    buf = buf.copyOfRange(nlIdx + 1, buf.size)
                    if (line.isEmpty()) continue
                    val msg = Json.parseToJsonElement(line).jsonObject
                    if (msg.containsKey("id")) return msg
                    continue
                }
                val tmp = ByteArray(8192)
                val n = sock.getInputStream().read(tmp)
                if (n <= 0) throw Exception("Connection closed")
                buf = buf + tmp.copyOfRange(0, n)
            }
        }
    }

    @Test
    @Order(1)
    fun `initialize handshake succeeds`() {
        val resp = sendJsonRpc("initialize", buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", JsonPrimitive("integration-test"))
                put("version", JsonPrimitive("1.0.0"))
            })
        })

        val result = resp["result"]!!.jsonObject
        assertNotNull(result["serverInfo"])
        assertNotNull(result["capabilities"])

        sendNotification("notifications/initialized")
        Thread.sleep(500)
    }

    @Test
    @Order(2)
    fun `tools list returns tools`() {
        val resp = sendJsonRpc("tools/list", buildJsonObject {})
        val tools = resp["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.size >= 10, "Expected at least 10 tools, got ${tools.size}")

        val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("get_debug_status" in toolNames)
        assertTrue("list_breakpoints" in toolNames)
        assertTrue("list_run_configurations" in toolNames)
    }

    @Test
    @Order(3)
    fun `list_run_configurations returns result`() {
        val resp = sendJsonRpc("tools/call", buildJsonObject {
            put("name", JsonPrimitive("list_run_configurations"))
            put("arguments", buildJsonObject {})
        })
        val content = resp["result"]!!.jsonObject["content"]!!.jsonArray
        assertTrue(content.isNotEmpty(), "Expected at least one content block")
        val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertTrue(parsed.containsKey("configurations"))
    }

    @Test
    @Order(4)
    fun `get_debug_status returns result`() {
        val resp = sendJsonRpc("tools/call", buildJsonObject {
            put("name", JsonPrimitive("get_debug_status"))
            put("arguments", buildJsonObject {})
        })
        val content = resp["result"]!!.jsonObject["content"]!!.jsonArray
        assertTrue(content.isNotEmpty())
    }

    @Test
    @Order(5)
    fun `list_breakpoints returns result`() {
        val resp = sendJsonRpc("tools/call", buildJsonObject {
            put("name", JsonPrimitive("list_breakpoints"))
            put("arguments", buildJsonObject {})
        })
        val content = resp["result"]!!.jsonObject["content"]!!.jsonArray
        assertTrue(content.isNotEmpty())
        val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertTrue(parsed.containsKey("breakpoints"))
    }

    @Test
    @Order(6)
    fun `unknown tool returns error`() {
        val resp = sendJsonRpc("tools/call", buildJsonObject {
            put("name", JsonPrimitive("nonexistent_tool"))
            put("arguments", buildJsonObject {})
        })
        // Either error in response or isError in result
        val hasError = resp.containsKey("error") ||
            resp["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true
        assertTrue(hasError, "Expected error for unknown tool")
    }
}
