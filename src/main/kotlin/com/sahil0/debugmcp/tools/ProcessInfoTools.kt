package com.sahil0.debugmcp.tools

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.openapi.project.Project
import com.sahil0.debugmcp.mcp.McpServer
import com.sahil0.debugmcp.mcp.CallToolResult
import com.sahil0.debugmcp.mcp.TextContent
import com.sahil0.debugmcp.mcp.ToolSchema
import kotlinx.serialization.json.*

fun registerProcessInfoTools(server: McpServer, project: Project) {

    // --- get_process_info ---
    server.addTool(
        name = "get_process_info",
        description = "Get info about the running debug process: PID, command line, JVM args, JVM version, run config name.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name (from get_debug_status)"))
                })
            },
            required = listOf()
        )
    ) { request ->
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull
        try {
            val session = getActiveDebugSession(project, sessionName)
                ?: throw IllegalStateException("No active debug session")

            val result = readAction {
                val debugProcess = session.debugProcess
                val processHandler = debugProcess.processHandler
                val runProfile = session.runProfile

                buildJsonObject {
                    // Run configuration info
                    put("runConfigName", runProfile?.name ?: "unknown")

                    // PID
                    if (processHandler is BaseProcessHandler<*>) {
                        try {
                            val pid = processHandler.process.pid()
                            put("pid", pid)
                        } catch (_: Exception) {
                            put("pid", "unavailable")
                        }

                        // Command line
                        val cmdLine = processHandler.commandLine
                        if (cmdLine != null) {
                            put("commandLine", cmdLine)
                            // Parse out JVM args
                            parseJvmArgs(cmdLine)?.let { args ->
                                put("jvmArgs", args)
                            }
                        }
                    }

                    // Session state
                    put("sessionName", session.sessionName)
                    put("isSuspended", session.isSuspended)
                    put("isPaused", session.isPaused)
                    put("isStopped", session.isStopped)

                    // Java-specific: JVM info via JDI
                    if (debugProcess is JavaDebugProcess) {
                        try {
                            val debuggerSession = debugProcess.debuggerSession
                            val impl = debuggerSession.process
                            val vmProxy = impl.virtualMachineProxy
                            put("jvmName", vmProxy.name())
                            put("jvmVersion", vmProxy.version())
                        } catch (_: Exception) {
                            put("jvmInfo", "unavailable (JDI not connected)")
                        }
                    }
                }
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to get process info: ${e.message}"))))
        }
    }
}

private fun parseJvmArgs(commandLine: String): JsonArray? {
    // Extract JVM flags (-X*, -D*, -ea, -agentlib, etc.) from a command line
    val parts = commandLine.split(" ")
    val jvmArgs = parts.filter { part ->
        part.startsWith("-X") ||
        part.startsWith("-D") ||
        part.startsWith("-ea") ||
        part.startsWith("-da") ||
        part.startsWith("-agentlib") ||
        part.startsWith("-agentpath") ||
        part.startsWith("-javaagent") ||
        part.startsWith("-verbose") ||
        part.startsWith("-server") ||
        part.startsWith("-client") ||
        part.startsWith("--add-opens") ||
        part.startsWith("--add-modules") ||
        part.startsWith("--add-exports") ||
        part.startsWith("--add-reads")
    }
    return if (jvmArgs.isNotEmpty()) JsonArray(jvmArgs.map { JsonPrimitive(it) }) else null
}
