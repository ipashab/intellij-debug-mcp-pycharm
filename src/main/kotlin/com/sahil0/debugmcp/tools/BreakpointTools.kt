package com.sahil0.debugmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.sahil0.debugmcp.mcp.McpServer
import com.sahil0.debugmcp.mcp.CallToolResult
import com.sahil0.debugmcp.mcp.TextContent
import com.sahil0.debugmcp.mcp.ToolSchema
import kotlinx.serialization.json.*

fun registerBreakpointTools(server: McpServer, project: Project) {

    // --- set_breakpoint ---
    server.addTool(
        name = "set_breakpoint",
        description = "Add a line breakpoint at file:line. Optionally set a condition expression.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Absolute path to the source file"))
                })
                put("line", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("1-based line number"))
                })
                put("condition", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional condition expression for the breakpoint"))
                })
            },
            required = listOf("line")
        )
    ) { request ->
        val filePath = request.arguments?.get("filePath")?.jsonPrimitive?.contentOrNull
        val line = request.arguments?.get("line")?.jsonPrimitive?.int
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing line"))))
        val condition = request.arguments?.get("condition")?.jsonPrimitive?.contentOrNull

        if (filePath == null) {
            return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Provide filePath"))))
        }

        try {
            val result = writeOnEdt {
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                    ?: throw IllegalArgumentException("File not found: $filePath")

                val bpManager = XDebuggerManager.getInstance(project).breakpointManager

                // Find the line breakpoint type from available types
                val allLineTypes = com.intellij.xdebugger.breakpoints.XBreakpointType.EXTENSION_POINT_NAME
                    .extensionList
                    .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>>()

                val bpType = allLineTypes.firstOrNull { it.canPutAt(vFile, line - 1, project) }
                    ?: throw IllegalArgumentException("Cannot set breakpoint at $filePath:$line")

                @Suppress("UNCHECKED_CAST")
                val typedBpType = bpType as com.intellij.xdebugger.breakpoints.XLineBreakpointType<com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>>
                val props = typedBpType.createBreakpointProperties(vFile, line - 1)
                val bp = bpManager.addLineBreakpoint(
                    typedBpType,
                    vFile.url,
                    line - 1, // Convert 1-based to 0-based
                    props
                )

                if (condition != null && bp != null) {
                    bp.conditionExpression = com.intellij.xdebugger.impl.breakpoints.XExpressionImpl.fromText(condition)
                }

                buildJsonObject {
                    put("status", "breakpoint_set")
                    put("file", vFile.url)
                    put("line", line)
                    if (condition != null) put("condition", condition)
                }
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to set breakpoint: ${e.message}"))))
        }
    }

    // --- remove_breakpoint ---
    server.addTool(
        name = "remove_breakpoint",
        description = "Remove a breakpoint at file:line.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Absolute path to the source file"))
                })
                put("line", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("1-based line number"))
                })
            },
            required = listOf("line")
        )
    ) { request ->
        val filePath = request.arguments?.get("filePath")?.jsonPrimitive?.contentOrNull
        val line = request.arguments?.get("line")?.jsonPrimitive?.int
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing line"))))

        if (filePath == null) {
            return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Provide filePath"))))
        }

        try {
            val result = writeOnEdt {
                val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                val targetLine = line - 1 // Convert to 0-based
                val matchUrl = filePath.replace("\\", "/")

                val matchingBp = bpManager.allBreakpoints
                    .filterIsInstance<XLineBreakpoint<*>>()
                    .find { bp ->
                        bp.line == targetLine && bp.fileUrl.contains(matchUrl)
                    }

                if (matchingBp != null) {
                    bpManager.removeBreakpoint(matchingBp)
                    buildJsonObject {
                        put("status", "breakpoint_removed")
                        put("file", filePath)
                        put("line", line)
                    }
                } else {
                    buildJsonObject {
                        put("status", "not_found")
                        put("message", "No breakpoint found at $filePath:$line")
                    }
                }
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to remove breakpoint: ${e.message}"))))
        }
    }

    // --- list_breakpoints ---
    server.addTool(
        name = "list_breakpoints",
        description = "List all current breakpoints",
    ) {
        try {
            val breakpoints = readAction {
                val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                bpManager.allBreakpoints.map { bp ->
                    buildJsonObject {
                        put("type", bp.type.id)
                        put("enabled", bp.isEnabled)
                        if (bp is XLineBreakpoint<*>) {
                            put("file", bp.fileUrl)
                            put("line", bp.line + 1) // Convert to 1-based
                            bp.conditionExpression?.let { put("condition", it.expression) }
                        }
                    }
                }
            }
            val result = buildJsonObject {
                put("breakpoints", JsonArray(breakpoints))
                put("count", breakpoints.size)
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to list breakpoints: ${e.message}"))))
        }
    }
}
