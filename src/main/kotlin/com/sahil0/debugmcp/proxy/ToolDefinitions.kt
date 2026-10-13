package com.sahil0.debugmcp.proxy

import org.json.JSONArray
import org.json.JSONObject

private val SESSION_NAME_PROP = JSONObject()
    .put("type", "string")
    .put("description", "Optional session name (from get_debug_status)")

private val PORT_PROP = JSONObject()
    .put("type", "integer")
    .put("description", "Optional IntelliJ Debug MCP port for routing this tool call to a specific IntelliJ instance")

private fun tool(
    name: String,
    description: String,
    properties: JSONObject = JSONObject(),
    required: List<String> = emptyList(),
    includeSessionName: Boolean = false,
    includePort: Boolean = true,
): JSONObject {
    val allProps = JSONObject(properties.toString())
    if (includeSessionName) allProps.put("sessionName", SESSION_NAME_PROP)
    if (includePort) allProps.put("port", PORT_PROP)

    val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", allProps)
    if (required.isNotEmpty()) {
        inputSchema.put("required", JSONArray(required))
    }

    return JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", inputSchema)
}

internal fun buildToolDefinitions(): JSONArray {
    val tools = JSONArray()

    tools.put(tool("list_run_configurations", "List all run/debug configurations in the project"))

    tools.put(tool(
        "start_debug_session",
        "Launch a run configuration in debug mode. Returns session info.",
        properties = JSONObject()
            .put("configName", JSONObject()
                .put("type", "string")
                .put("description", "Name of the run configuration to debug")),
        required = listOf("configName")
    ))

    tools.put(tool(
        "stop_debug_session",
        "Stop a debug session. If sessionName is omitted, stops the current/first active session.",
        includeSessionName = true
    ))

    tools.put(tool(
        "get_debug_status",
        "Get debug session status. Shows all sessions if sessionName is omitted, or a specific session if provided.",
        includeSessionName = true
    ))

    tools.put(tool(
        "set_breakpoint",
        "Add a line breakpoint at file:line. Use filePath for local source, or className for JAR/library classes.",
        properties = JSONObject()
            .put("filePath", JSONObject()
                .put("type", "string")
                .put("description", "Absolute path to the source file (use this OR className)"))
            .put("className", JSONObject()
                .put("type", "string")
                .put("description", "Fully qualified class name, e.g. com.example.MyClass (use this OR filePath). Works for JAR/library classes."))
            .put("line", JSONObject()
                .put("type", "integer")
                .put("description", "1-based line number"))
            .put("condition", JSONObject()
                .put("type", "string")
                .put("description", "Optional condition expression")),
        required = listOf("line")
    ))

    tools.put(tool(
        "remove_breakpoint",
        "Remove a breakpoint at file:line. Use filePath for local source, or className for JAR/library classes.",
        properties = JSONObject()
            .put("filePath", JSONObject()
                .put("type", "string")
                .put("description", "Absolute path to the source file (use this OR className)"))
            .put("className", JSONObject()
                .put("type", "string")
                .put("description", "Fully qualified class name (use this OR filePath)"))
            .put("line", JSONObject()
                .put("type", "integer")
                .put("description", "1-based line number")),
        required = listOf("line")
    ))

    tools.put(tool("list_breakpoints", "List all current breakpoints"))

    tools.put(tool("resume", "Resume execution of the suspended debug session", includeSessionName = true))
    tools.put(tool("step_over", "Step over the current line", includeSessionName = true))
    tools.put(tool("step_into", "Step into the current method call", includeSessionName = true))
    tools.put(tool("step_out", "Step out of the current method", includeSessionName = true))
    tools.put(tool("get_threads", "List all threads with their status in the current debug session", includeSessionName = true))

    tools.put(tool(
        "get_stack_trace",
        "Get stack frames for the current (or specified) thread",
        properties = JSONObject()
            .put("threadName", JSONObject()
                .put("type", "string")
                .put("description", "Optional thread name")),
        includeSessionName = true
    ))

    tools.put(tool(
        "get_variables",
        "Get local variables and their values for the current stack frame",
        properties = JSONObject()
            .put("frameIndex", JSONObject()
                .put("type", "integer")
                .put("description", "Optional 0-based frame index")),
        includeSessionName = true
    ))

    tools.put(tool(
        "evaluate_expression",
        "Evaluate an expression in the current debug context",
        properties = JSONObject()
            .put("expression", JSONObject()
                .put("type", "string")
                .put("description", "Java/Kotlin expression to evaluate")),
        required = listOf("expression"),
        includeSessionName = true
    ))

    tools.put(tool(
        "get_console_output",
        "Retrieve recent console/stdout/stderr output from the debug process. Works even after the process has exited.",
        properties = JSONObject()
            .put("maxLines", JSONObject()
                .put("type", "integer")
                .put("description", "Max lines to return (default 50)")),
        includeSessionName = true
    ))

    tools.put(tool(
        "send_console_input",
        "Send text input to the debug process's stdin (followed by a newline). Use this to interact with CLI prompts, Spring Shell, or any console runner that reads from System.in.",
        properties = JSONObject()
            .put("text", JSONObject()
                .put("type", "string")
                .put("description", "The text to send to stdin (a newline will be appended automatically)")),
        required = listOf("text"),
        includeSessionName = true
    ))

    tools.put(tool(
        "get_process_info",
        "Get info about the running debug process: PID, command line, run config name.",
        includeSessionName = true
    ))

    return tools
}
