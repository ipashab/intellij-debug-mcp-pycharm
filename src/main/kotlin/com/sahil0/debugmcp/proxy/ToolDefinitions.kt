package com.sahil0.debugmcp.proxy

import kotlinx.serialization.json.*

private val SESSION_NAME_PROP = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put("description", JsonPrimitive("Optional session name (from get_debug_status)"))
}

private val PORT_PROP = buildJsonObject {
    put("type", JsonPrimitive("integer"))
    put("description", JsonPrimitive("Optional IntelliJ Debug MCP port for routing this tool call to a specific IntelliJ instance"))
}

private fun tool(
    name: String,
    description: String,
    properties: JsonObject = buildJsonObject {},
    required: List<String> = emptyList(),
    includeSessionName: Boolean = false,
    includePort: Boolean = true,
): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("description", JsonPrimitive(description))
    put("inputSchema", buildJsonObject {
        put("type", JsonPrimitive("object"))
        val allProps = buildJsonObject {
            for ((k, v) in properties) put(k, v)
            if (includeSessionName) put("sessionName", SESSION_NAME_PROP)
            if (includePort) put("port", PORT_PROP)
        }
        put("properties", allProps)
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    })
}

internal fun buildToolDefinitions(): JsonArray = buildJsonArray {
    add(tool("list_run_configurations", "List all run/debug configurations in the project"))

    add(tool(
        "start_debug_session",
        "Launch a run configuration in debug mode. Returns session info.",
        properties = buildJsonObject {
            put("configName", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Name of the run configuration to debug"))
            })
        },
        required = listOf("configName")
    ))

    add(tool(
        "stop_debug_session",
        "Stop a debug session. If sessionName is omitted, stops the current/first active session.",
        includeSessionName = true
    ))

    add(tool(
        "get_debug_status",
        "Get debug session status. Shows all sessions if sessionName is omitted, or a specific session if provided.",
        includeSessionName = true
    ))

    add(tool(
        "set_breakpoint",
        "Add a line breakpoint at file:line. Use filePath for local source, or className for JAR/library classes.",
        properties = buildJsonObject {
            put("filePath", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Absolute path to the source file (use this OR className)"))
            })
            put("className", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Fully qualified class name, e.g. com.example.MyClass (use this OR filePath). Works for JAR/library classes."))
            })
            put("line", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("1-based line number"))
            })
            put("condition", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional condition expression"))
            })
        },
        required = listOf("line")
    ))

    add(tool(
        "remove_breakpoint",
        "Remove a breakpoint at file:line. Use filePath for local source, or className for JAR/library classes.",
        properties = buildJsonObject {
            put("filePath", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Absolute path to the source file (use this OR className)"))
            })
            put("className", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Fully qualified class name (use this OR filePath)"))
            })
            put("line", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("1-based line number"))
            })
        },
        required = listOf("line")
    ))

    add(tool("list_breakpoints", "List all current breakpoints"))

    add(tool("resume", "Resume execution of the suspended debug session", includeSessionName = true))
    add(tool("step_over", "Step over the current line", includeSessionName = true))
    add(tool("step_into", "Step into the current method call", includeSessionName = true))
    add(tool("step_out", "Step out of the current method", includeSessionName = true))
    add(tool("get_threads", "List all threads with their status in the current debug session", includeSessionName = true))

    add(tool(
        "get_stack_trace",
        "Get stack frames for the current (or specified) thread",
        properties = buildJsonObject {
            put("threadName", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional thread name"))
            })
        },
        includeSessionName = true
    ))

    add(tool(
        "get_variables",
        "Get local variables and their values for the current stack frame",
        properties = buildJsonObject {
            put("frameIndex", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Optional 0-based frame index"))
            })
        },
        includeSessionName = true
    ))

    add(tool(
        "evaluate_expression",
        "Evaluate an expression in the current debug context",
        properties = buildJsonObject {
            put("expression", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Java/Kotlin expression to evaluate"))
            })
        },
        required = listOf("expression"),
        includeSessionName = true
    ))

    add(tool(
        "get_console_output",
        "Retrieve recent console/stdout/stderr output from the debug process. Works even after the process has exited.",
        properties = buildJsonObject {
            put("maxLines", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Max lines to return (default 50)"))
            })
        },
        includeSessionName = true
    ))

    add(tool(
        "send_console_input",
        "Send text input to the debug process's stdin (followed by a newline). Use this to interact with CLI prompts, Spring Shell, or any console runner that reads from System.in.",
        properties = buildJsonObject {
            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The text to send to stdin (a newline will be appended automatically)"))
            })
        },
        required = listOf("text"),
        includeSessionName = true
    ))

    add(tool(
        "get_process_info",
        "Get info about the running debug process: PID, command line, JVM args, JVM version, run config name.",
        includeSessionName = true
    ))

    add(tool(
        "decompile_class",
        "Read decompiled source of a class from the project classpath (including dependency JARs).",
        properties = buildJsonObject {
            put("className", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Fully qualified class name, e.g. com.example.MyClass"))
            })
            put("lineStart", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Optional 1-based start line"))
            })
            put("lineEnd", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Optional 1-based end line (inclusive)"))
            })
        },
        required = listOf("className")
    ))
}
