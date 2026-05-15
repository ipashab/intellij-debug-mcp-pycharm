package com.sahil0.debugmcp.mcp

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpSessionTest {

    private fun buildServer(): McpServer {
        val server = McpServer(serverName = "session-test", serverVersion = "0.0.1")
        server.addTool(name = "greet", description = "Greets user") { req ->
            val name = req.arguments?.get("name")?.jsonPrimitive?.contentOrNull ?: "world"
            CallToolResult(content = listOf(TextContent(text = """{"greeting":"Hello, $name!"}""")))
        }
        server.addTool(name = "add", description = "Adds two numbers",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("a", buildJsonObject { put("type", "integer") })
                    put("b", buildJsonObject { put("type", "integer") })
                },
                required = listOf("a", "b")
            )
        ) { req ->
            val a = req.arguments!!["a"]!!.jsonPrimitive.int
            val b = req.arguments!!["b"]!!.jsonPrimitive.int
            CallToolResult(content = listOf(TextContent(text = """{"sum":${a + b}}""")))
        }
        return server
    }

    private fun jsonRpcLine(method: String, id: Int, params: JsonObject? = null): String {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        return Json.encodeToString(msg)
    }

    private fun notificationLine(method: String): String {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        return Json.encodeToString(msg)
    }

    // ── Full session over byte streams ──

    @Test
    fun `full handshake and tool call over streams`() = runTest {
        val input = listOf(
            jsonRpcLine("initialize", 1, buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "test")
                    put("version", "1.0")
                })
            }),
            notificationLine("notifications/initialized"),
            jsonRpcLine("tools/list", 2),
            jsonRpcLine("tools/call", 3, buildJsonObject {
                put("name", "greet")
                put("arguments", buildJsonObject { put("name", "Alice") })
            }),
            jsonRpcLine("tools/call", 4, buildJsonObject {
                put("name", "add")
                put("arguments", buildJsonObject {
                    put("a", 3)
                    put("b", 7)
                })
            }),
        ).joinToString("\n") + "\n"

        val inputStream = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))
        val outputStream = ByteArrayOutputStream()

        runMcpSession(inputStream, outputStream, buildServer())

        val responses = outputStream.toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

        // Should have 4 responses (initialize, tools/list, 2x tools/call)
        // notifications/initialized produces no response
        assertEquals(4, responses.size)

        // 1. initialize
        val initResp = responses[0]
        assertEquals(1, initResp["id"]!!.jsonPrimitive.int)
        assertEquals("2024-11-05", initResp["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)

        // 2. tools/list
        val listResp = responses[1]
        assertEquals(2, listResp["id"]!!.jsonPrimitive.int)
        val tools = listResp["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("greet", "add"), toolNames)

        // 3. tools/call greet
        val greetResp = responses[2]
        assertEquals(3, greetResp["id"]!!.jsonPrimitive.int)
        val greetText = greetResp["result"]!!.jsonObject["content"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        val greeting = Json.parseToJsonElement(greetText).jsonObject
        assertEquals("Hello, Alice!", greeting["greeting"]!!.jsonPrimitive.content)

        // 4. tools/call add
        val addResp = responses[3]
        assertEquals(4, addResp["id"]!!.jsonPrimitive.int)
        val addText = addResp["result"]!!.jsonObject["content"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        val sum = Json.parseToJsonElement(addText).jsonObject
        assertEquals(10, sum["sum"]!!.jsonPrimitive.int)
    }

    // ── Empty input ──

    @Test
    fun `empty input stream ends session gracefully`() = runTest {
        val inputStream = ByteArrayInputStream(ByteArray(0))
        val outputStream = ByteArrayOutputStream()
        runMcpSession(inputStream, outputStream, buildServer())
        assertEquals("", outputStream.toString(Charsets.UTF_8))
    }

    // ── Blank lines are ignored ──

    @Test
    fun `blank lines between messages are ignored`() = runTest {
        val input = "\n\n${jsonRpcLine("ping", 1)}\n\n\n${jsonRpcLine("ping", 2)}\n\n"
        val inputStream = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))
        val outputStream = ByteArrayOutputStream()

        runMcpSession(inputStream, outputStream, buildServer())

        val responses = outputStream.toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

        assertEquals(2, responses.size)
        assertEquals(1, responses[0]["id"]!!.jsonPrimitive.int)
        assertEquals(2, responses[1]["id"]!!.jsonPrimitive.int)
    }

    // ── Malformed JSON is skipped ──

    @Test
    fun `malformed JSON line is skipped without crashing`() = runTest {
        val input = listOf(
            "this is not json",
            jsonRpcLine("ping", 1),
            "{invalid",
            jsonRpcLine("ping", 2),
        ).joinToString("\n") + "\n"

        val inputStream = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))
        val outputStream = ByteArrayOutputStream()

        runMcpSession(inputStream, outputStream, buildServer())

        val responses = outputStream.toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

        // Only the two valid pings should produce responses
        assertEquals(2, responses.size)
    }

    // ── TCP-like piped stream session ──

    @Test
    fun `session works over piped streams (simulated TCP)`() = runTest {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut)
        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut)

        val server = buildServer()

        // Run session in background
        val sessionJob = launch {
            runMcpSession(serverIn, serverOut, server)
        }

        // Client sends initialize
        val initMsg = jsonRpcLine("initialize", 1) + "\n"
        clientOut.write(initMsg.toByteArray(Charsets.UTF_8))
        clientOut.flush()

        // Read response
        val reader = clientIn.bufferedReader(Charsets.UTF_8)
        val initLine = reader.readLine()
        assertNotNull(initLine)
        val initResp = Json.parseToJsonElement(initLine).jsonObject
        assertEquals(1, initResp["id"]!!.jsonPrimitive.int)
        assertNotNull(initResp["result"]!!.jsonObject["serverInfo"])

        // Client sends tool call
        val callMsg = jsonRpcLine("tools/call", 2, buildJsonObject {
            put("name", "greet")
            put("arguments", buildJsonObject { put("name", "Bob") })
        }) + "\n"
        clientOut.write(callMsg.toByteArray(Charsets.UTF_8))
        clientOut.flush()

        val callLine = reader.readLine()
        assertNotNull(callLine)
        val callResp = Json.parseToJsonElement(callLine).jsonObject
        assertEquals(2, callResp["id"]!!.jsonPrimitive.int)
        val text = callResp["result"]!!.jsonObject["content"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Bob"))

        // Close client → session should end
        clientOut.close()
        sessionJob.join()
    }
}
