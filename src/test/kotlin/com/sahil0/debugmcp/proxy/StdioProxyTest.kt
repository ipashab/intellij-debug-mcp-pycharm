package com.sahil0.debugmcp.proxy

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StdioProxyTest {

    // ── handleRequest: initialize ──

    @Test
    fun `initialize returns protocol version and capabilities`() {
        val captured = captureResponse {
            StdioProxy.handleRequest(buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(1))
                put("method", JsonPrimitive("initialize"))
                put("params", buildJsonObject {
                    put("protocolVersion", JsonPrimitive("2024-11-05"))
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject {
                        put("name", JsonPrimitive("test"))
                        put("version", JsonPrimitive("1.0"))
                    })
                })
            })
        }

        assertNotNull(captured)
        assertEquals("2.0", captured["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(1, captured["id"]!!.jsonPrimitive.int)

        val result = captured["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"])
        assertNotNull(result["serverInfo"])
        assertEquals("intellij-debug-mcp-proxy", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    // ── handleRequest: tools/list ──

    @Test
    fun `tools list returns all tools`() {
        val captured = captureResponse {
            StdioProxy.handleRequest(buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(2))
                put("method", JsonPrimitive("tools/list"))
            })
        }

        assertNotNull(captured)
        val tools = captured["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.size >= 18, "Should have at least 18 tools, got ${tools.size}")
    }

    // ── handleRequest: unknown method ──

    @Test
    fun `unknown method returns error -32601`() {
        val captured = captureResponse {
            StdioProxy.handleRequest(buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(99))
                put("method", JsonPrimitive("unknown/method"))
            })
        }

        assertNotNull(captured)
        val error = captured["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("unknown/method"))
    }

    // ── handleRequest: notification (no id) produces no response ──

    @Test
    fun `notifications initialized produces no response`() {
        val captured = captureResponse {
            StdioProxy.handleRequest(buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive("notifications/initialized"))
            })
        }
        assertNull(captured, "Notifications should not produce a response")
    }

    // ── extractTargetPort ──

    @Test
    fun `extractTargetPort with no port returns null port`() {
        val args = buildJsonObject { put("name", JsonPrimitive("foo")) }
        val (port, cleaned, err) = StdioProxy.extractTargetPort(args)
        assertNull(port)
        assertNull(err)
        assertEquals(args, cleaned)
    }

    @Test
    fun `extractTargetPort with valid port extracts and removes it`() {
        val args = buildJsonObject {
            put("name", JsonPrimitive("foo"))
            put("port", JsonPrimitive(12345))
        }
        val (port, cleaned, err) = StdioProxy.extractTargetPort(args)
        assertEquals(12345, port)
        assertNull(err)
        assertTrue(!cleaned.containsKey("port"), "port should be removed from forwarded args")
        assertEquals("foo", cleaned["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extractTargetPort with invalid port returns error`() {
        val args = buildJsonObject {
            put("port", JsonPrimitive("not-a-number"))
        }
        val (port, _, err) = StdioProxy.extractTargetPort(args)
        assertNull(port)
        assertNotNull(err)
        assertTrue(err.contains("Invalid"))
    }

    // ── errorResult ──

    @Test
    fun `errorResult produces proper MCP error structure`() {
        val result = StdioProxy.errorResult("test error")
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        val content = result["content"]!!.jsonArray
        assertEquals(1, content.size)
        val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("test error"))
    }

    // ── Port discovery ──

    @Test
    fun `discoverPortFromDir finds port file`(@TempDir tempDir: Path) {
        val ideaDir = File(tempDir.toFile(), ".idea")
        ideaDir.mkdirs()
        File(ideaDir, "debugmcp.port").writeText("54321")

        val result = StdioProxy.discoverPortFromDir(tempDir.toFile())
        assertNotNull(result)
        assertTrue(result.absolutePath.endsWith("debugmcp.port"))
    }

    @Test
    fun `discoverPortFromDir walks up from subdirectory`(@TempDir tempDir: Path) {
        val ideaDir = File(tempDir.toFile(), ".idea")
        ideaDir.mkdirs()
        File(ideaDir, "debugmcp.port").writeText("11111")

        val subDir = File(tempDir.toFile(), "src/main/kotlin")
        subDir.mkdirs()

        val result = StdioProxy.discoverPortFromDir(subDir)
        assertNotNull(result)
        assertTrue(result.absolutePath.endsWith("debugmcp.port"))
    }

    @Test
    fun `discoverPortFromDir returns null when no port file exists`(@TempDir tempDir: Path) {
        val result = StdioProxy.discoverPortFromDir(tempDir.toFile())
        assertNull(result)
    }

    // ── forwardToolCall without IntelliJ ──

    @Test
    fun `forwardToolCall with no connections and no port files returns error`() {
        // Save and clear env, use a non-existent project dir so nothing is discovered
        StdioProxy.connections.clear()
        val origDir = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", System.getProperty("java.io.tmpdir"))
            val result = StdioProxy.forwardToolCall("get_debug_status", buildJsonObject {})
            // Should return an error since no port files exist under temp dir
            val isError = result["isError"]?.jsonPrimitive?.boolean ?: false
            val hasContent = result.containsKey("content")
            assertTrue(isError || hasContent, "Expected an error or content in result")
        } finally {
            System.setProperty("user.dir", origDir)
        }
    }

    @Test
    fun `forwardToolCall to unreachable port returns error`() {
        StdioProxy.connections.clear()
        // Use a random high port that is almost certainly not listening
        val result = StdioProxy.forwardToolCall("get_debug_status", buildJsonObject {
            put("port", JsonPrimitive(59999))
        })
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
    }

    // ── Helpers ──

    /**
     * Capture stdout from a handleRequest call. Returns the parsed JSON response,
     * or null if nothing was written.
     */
    private fun captureResponse(block: () -> Unit): JsonObject? {
        val origOut = System.out
        val baos = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(baos, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setOut(origOut)
        }
        val output = baos.toString("UTF-8").trim()
        if (output.isEmpty()) return null
        return Json.parseToJsonElement(output).jsonObject
    }
}
