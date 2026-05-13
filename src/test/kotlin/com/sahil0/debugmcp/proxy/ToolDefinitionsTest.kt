package com.sahil0.debugmcp.proxy

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolDefinitionsTest {

    private val tools = buildToolDefinitions()

    @Test
    fun `all 19 tools are defined`() {
        assertEquals(19, tools.size, "Expected 19 tool definitions")
    }

    @Test
    fun `every tool has name, description, and inputSchema`() {
        for (element in tools) {
            val tool = element.jsonObject
            assertTrue(tool.containsKey("name"), "Tool missing 'name'")
            assertTrue(tool.containsKey("description"), "Tool missing 'description'")
            assertTrue(tool.containsKey("inputSchema"), "Tool ${tool["name"]} missing 'inputSchema'")
        }
    }

    @Test
    fun `every tool has port property for routing`() {
        for (element in tools) {
            val tool = element.jsonObject
            val schema = tool["inputSchema"]!!.jsonObject
            val props = schema["properties"]?.jsonObject ?: continue
            assertTrue(
                props.containsKey("port"),
                "Tool '${tool["name"]!!.jsonPrimitive.content}' missing 'port' property"
            )
        }
    }

    @Test
    fun `tools with required params declare them`() {
        val requirementsMap = mapOf(
            "start_debug_session" to listOf("configName"),
            "set_breakpoint" to listOf("line"),
            "remove_breakpoint" to listOf("line"),
            "evaluate_expression" to listOf("expression"),
            "send_console_input" to listOf("text"),
            "decompile_class" to listOf("className"),
        )

        for (element in tools) {
            val tool = element.jsonObject
            val name = tool["name"]!!.jsonPrimitive.content
            val expectedRequired = requirementsMap[name] ?: continue

            val schema = tool["inputSchema"]!!.jsonObject
            val actual = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertEquals(
                expectedRequired.sorted(), actual.sorted(),
                "Tool '$name' has wrong required params"
            )
        }
    }

    @Test
    fun `tool names match expected set`() {
        val expected = setOf(
            "list_run_configurations", "start_debug_session", "stop_debug_session",
            "get_debug_status", "set_breakpoint", "remove_breakpoint", "list_breakpoints",
            "resume", "step_over", "step_into", "step_out", "get_threads",
            "get_stack_trace", "get_variables", "evaluate_expression",
            "get_console_output", "send_console_input", "get_process_info",
            "decompile_class"
        )
        val actual = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `session-aware tools include sessionName property`() {
        val sessionTools = setOf(
            "stop_debug_session", "get_debug_status", "resume", "step_over",
            "step_into", "step_out", "get_threads", "get_stack_trace",
            "get_variables", "evaluate_expression", "get_console_output",
            "send_console_input", "get_process_info"
        )
        for (element in tools) {
            val tool = element.jsonObject
            val name = tool["name"]!!.jsonPrimitive.content
            if (name in sessionTools) {
                val props = tool["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
                assertTrue(
                    props.containsKey("sessionName"),
                    "Tool '$name' should have 'sessionName' property"
                )
            }
        }
    }
}
