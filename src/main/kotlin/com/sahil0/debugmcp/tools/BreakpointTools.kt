package com.sahil0.debugmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Resolve a fully qualified class name to its VirtualFile (works for source and decompiled JAR classes).
 */
private fun resolveClassFile(project: Project, className: String): VirtualFile {
    return readAction {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.allScope(project))
            ?: throw IllegalArgumentException("Class not found on classpath: $className")
        psiClass.containingFile?.virtualFile
            ?: throw IllegalStateException("Cannot access file for class: $className")
    }
}

fun registerBreakpointTools(server: Server, project: Project) {

    // --- set_breakpoint ---
    server.addTool(
        name = "set_breakpoint",
        description = "Add a line breakpoint at file:line. Optionally set a condition expression. " +
                "Use filePath for local source files, or className for library/JAR classes (mutually exclusive).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Absolute path to the source file (use this OR className, not both)"))
                })
                put("className", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Fully qualified class name, e.g. com.example.MyClass (use this OR filePath, not both). Works for JAR/library classes."))
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
        val className = request.arguments?.get("className")?.jsonPrimitive?.contentOrNull
        val line = request.arguments?.get("line")?.jsonPrimitive?.int
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing line"))))
        val condition = request.arguments?.get("condition")?.jsonPrimitive?.contentOrNull

        if (filePath == null && className == null) {
            return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Provide either filePath or className"))))
        }

        try {
            val result = writeOnEdt {
                val vFile = if (className != null) {
                    resolveClassFile(project, className)
                } else {
                    LocalFileSystem.getInstance().findFileByPath(filePath!!)
                        ?: throw IllegalArgumentException("File not found: $filePath")
                }

                val bpManager = XDebuggerManager.getInstance(project).breakpointManager

                // Find the line breakpoint type from available types
                val allLineTypes = com.intellij.xdebugger.breakpoints.XBreakpointType.EXTENSION_POINT_NAME
                    .extensionList
                    .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>>()

                val bpType = if (className != null) {
                    // For JAR/decompiled classes, canPutAt() fails on .class VirtualFiles.
                    // Try canPutAt first, then fall back to the Java line breakpoint type directly.
                    allLineTypes.firstOrNull { it.canPutAt(vFile, line - 1, project) }
                        ?: allLineTypes.firstOrNull { it.id == "java-line" }
                        ?: throw IllegalArgumentException("Java line breakpoint type not available")
                } else {
                    allLineTypes.firstOrNull { it.canPutAt(vFile, line - 1, project) }
                        ?: throw IllegalArgumentException("Cannot set breakpoint at $filePath:$line")
                }

                @Suppress("UNCHECKED_CAST")
                val typedBpType = bpType as com.intellij.xdebugger.breakpoints.XLineBreakpointType<XBreakpointProperties<*>>
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
                    if (className != null) put("className", className)
                    put("file", filePath ?: vFile.url)
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
        description = "Remove a breakpoint at file:line. Use filePath for local source files, or className for library/JAR classes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Absolute path to the source file (use this OR className)"))
                })
                put("className", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Fully qualified class name, e.g. com.example.MyClass (use this OR filePath)"))
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
        val className = request.arguments?.get("className")?.jsonPrimitive?.contentOrNull
        val line = request.arguments?.get("line")?.jsonPrimitive?.int
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing line"))))

        if (filePath == null && className == null) {
            return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Provide either filePath or className"))))
        }

        try {
            val result = writeOnEdt {
                val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                val targetLine = line - 1 // Convert to 0-based

                // Resolve the file URL to match against
                val matchUrl = if (className != null) {
                    resolveClassFile(project, className).url
                } else {
                    filePath!!.replace("\\", "/")
                }

                val matchingBp = bpManager.allBreakpoints
                    .filterIsInstance<XLineBreakpoint<*>>()
                    .find { bp ->
                        bp.line == targetLine && bp.fileUrl.contains(matchUrl)
                    }

                if (matchingBp != null) {
                    bpManager.removeBreakpoint(matchingBp)
                    buildJsonObject {
                        put("status", "breakpoint_removed")
                        if (className != null) put("className", className)
                        put("file", filePath ?: matchUrl)
                        put("line", line)
                    }
                } else {
                    val location = className ?: filePath!!
                    buildJsonObject {
                        put("status", "not_found")
                        put("message", "No breakpoint found at $location:$line")
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
