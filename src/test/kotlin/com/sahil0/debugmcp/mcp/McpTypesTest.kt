package com.sahil0.debugmcp.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTypesTest {

    // ── ToolSchema ──

    @Test
    fun `ToolSchema defaults to empty properties and required`() {
        val schema = ToolSchema()
        assertTrue(schema.properties.isEmpty())
        assertTrue(schema.required.isEmpty())
    }

    @Test
    fun `ToolSchema with properties and required`() {
        val schema = ToolSchema(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
                put("age", buildJsonObject { put("type", "integer") })
            },
            required = listOf("name")
        )
        assertEquals(2, schema.properties.size)
        assertEquals(listOf("name"), schema.required)
    }

    // ── TextContent ──

    @Test
    fun `TextContent holds text`() {
        val tc = TextContent(text = "hello world")
        assertEquals("hello world", tc.text)
    }

    @Test
    fun `TextContent equality`() {
        assertEquals(TextContent("a"), TextContent("a"))
    }

    // ── CallToolResult ──

    @Test
    fun `CallToolResult defaults isError to false`() {
        val result = CallToolResult(content = listOf(TextContent("ok")))
        assertFalse(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("ok", result.content[0].text)
    }

    @Test
    fun `CallToolResult with isError true`() {
        val result = CallToolResult(content = listOf(TextContent("fail")), isError = true)
        assertTrue(result.isError)
    }

    @Test
    fun `CallToolResult with multiple content items`() {
        val result = CallToolResult(content = listOf(
            TextContent("a"),
            TextContent("b"),
            TextContent("c")
        ))
        assertEquals(3, result.content.size)
    }

    @Test
    fun `CallToolResult with empty content`() {
        val result = CallToolResult(content = emptyList())
        assertTrue(result.content.isEmpty())
    }

    // ── CallToolRequest ──

    @Test
    fun `CallToolRequest defaults arguments to null`() {
        val req = CallToolRequest()
        assertNull(req.arguments)
    }

    @Test
    fun `CallToolRequest with arguments`() {
        val args = buildJsonObject { put("key", "value") }
        val req = CallToolRequest(arguments = args)
        assertEquals("value", req.arguments!!["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `CallToolRequest with empty arguments`() {
        val req = CallToolRequest(arguments = buildJsonObject {})
        assertTrue(req.arguments!!.isEmpty())
    }
}
