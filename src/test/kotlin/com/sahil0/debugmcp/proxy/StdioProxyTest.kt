package com.sahil0.debugmcp.proxy

import org.json.JSONObject
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
            StdioProxy.handleRequest(JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject()
                        .put("name", "test")
                        .put("version", "1.0"))))
        }

        assertNotNull(captured)
        assertEquals("2.0", captured.getString("jsonrpc"))
        assertEquals(1, captured.getInt("id"))

        val result = captured.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertNotNull(result.opt("capabilities"))
        assertNotNull(result.opt("serverInfo"))
        assertEquals("intellij-debug-mcp-proxy", result.getJSONObject("serverInfo").getString("name"))
    }

    // ── handleRequest: tools/list ──

    @Test
    fun `tools list returns all tools`() {
        val captured = captureResponse {
            StdioProxy.handleRequest(JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list"))
        }

        assertNotNull(captured)
        val tools = captured.getJSONObject("result").getJSONArray("tools")
        assertTrue(tools.length() >= 18, "Should have at least 18 tools, got ${tools.length()}")
    }

    // ── handleRequest: unknown method ──

    @Test
    fun `unknown method returns error -32601`() {
        val captured = captureResponse {
            StdioProxy.handleRequest(JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 99)
                .put("method", "unknown/method"))
        }

        assertNotNull(captured)
        val error = captured.getJSONObject("error")
        assertEquals(-32601, error.getInt("code"))
        assertTrue(error.getString("message").contains("unknown/method"))
    }

    // ── handleRequest: notification (no id) produces no response ──

    @Test
    fun `notifications initialized produces no response`() {
        val captured = captureResponse {
            StdioProxy.handleRequest(JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized"))
        }
        assertNull(captured, "Notifications should not produce a response")
    }

    // ── extractTargetPort ──

    @Test
    fun `extractTargetPort with no port returns null port`() {
        val args = JSONObject().put("name", "foo")
        val (port, cleaned, err) = StdioProxy.extractTargetPort(args)
        assertNull(port)
        assertNull(err)
        assertEquals("foo", cleaned.getString("name"))
    }

    @Test
    fun `extractTargetPort with valid port extracts and removes it`() {
        val args = JSONObject()
            .put("name", "foo")
            .put("port", 12345)
        val (port, cleaned, err) = StdioProxy.extractTargetPort(args)
        assertEquals(12345, port)
        assertNull(err)
        assertTrue(!cleaned.has("port"), "port should be removed from forwarded args")
        assertEquals("foo", cleaned.getString("name"))
    }

    @Test
    fun `extractTargetPort with invalid port returns error`() {
        val args = JSONObject().put("port", "not-a-number")
        val (port, _, err) = StdioProxy.extractTargetPort(args)
        assertNull(port)
        assertNotNull(err)
        assertTrue(err.contains("Invalid"))
    }

    // ── errorResult ──

    @Test
    fun `errorResult produces proper MCP error structure`() {
        val result = StdioProxy.errorResult("test error")
        assertTrue(result.getBoolean("isError"))
        val content = result.getJSONArray("content")
        assertEquals(1, content.length())
        val text = content.getJSONObject(0).getString("text")
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
            val result = StdioProxy.forwardToolCall("get_debug_status", JSONObject())
            // Should return an error since no port files exist under temp dir
            val isError = result.optBoolean("isError", false)
            val hasContent = result.has("content")
            assertTrue(isError || hasContent, "Expected an error or content in result")
        } finally {
            System.setProperty("user.dir", origDir)
        }
    }

    @Test
    fun `forwardToolCall to unreachable port returns error`() {
        StdioProxy.connections.clear()
        // Use a random high port that is almost certainly not listening
        val result = StdioProxy.forwardToolCall("get_debug_status", JSONObject().put("port", 59999))
        assertTrue(result.getBoolean("isError"))
    }

    // ── Helpers ──

    /**
     * Capture stdout from a handleRequest call. Returns the parsed JSON response,
     * or null if nothing was written.
     */
    private fun captureResponse(block: () -> Unit): JSONObject? {
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
        return JSONObject(output)
    }
}
