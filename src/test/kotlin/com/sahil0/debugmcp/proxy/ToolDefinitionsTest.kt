package com.sahil0.debugmcp.proxy

import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolDefinitionsTest {

    private val tools = buildToolDefinitions()

    @Test
    fun `all 19 tools are defined`() {
        assertEquals(19, tools.length(), "Expected 19 tool definitions")
    }

    @Test
    fun `every tool has name, description, and inputSchema`() {
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            assertTrue(tool.has("name"), "Tool missing 'name'")
            assertTrue(tool.has("description"), "Tool missing 'description'")
            assertTrue(tool.has("inputSchema"), "Tool ${tool.opt("name")} missing 'inputSchema'")
        }
    }

    @Test
    fun `every tool has port property for routing`() {
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            val schema = tool.getJSONObject("inputSchema")
            val props = schema.optJSONObject("properties") ?: continue
            assertTrue(
                props.has("port"),
                "Tool '${tool.getString("name")}' missing 'port' property"
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

        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            val name = tool.getString("name")
            val expectedRequired = requirementsMap[name] ?: continue

            val schema = tool.getJSONObject("inputSchema")
            val requiredArr = schema.optJSONArray("required")
            val actual = if (requiredArr != null) {
                (0 until requiredArr.length()).map { requiredArr.getString(it) }
            } else emptyList()
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
        val actual = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
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
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            val name = tool.getString("name")
            if (name in sessionTools) {
                val props = tool.getJSONObject("inputSchema").getJSONObject("properties")
                assertTrue(
                    props.has("sessionName"),
                    "Tool '$name' should have 'sessionName' property"
                )
            }
        }
    }
}
