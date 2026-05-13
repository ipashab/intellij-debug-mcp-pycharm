package com.sahil0.debugmcp.tools

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.project.Project
import com.intellij.problems.WolfTheProblemSolver
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

fun registerRunConfigTools(server: Server, project: Project) {

    // --- list_run_configurations ---
    server.addTool(
        name = "list_run_configurations",
        description = "List all run/debug configurations in the project",
    ) {
        val configs = readAction {
            RunManager.getInstance(project).allConfigurationsList.map { config ->
                buildJsonObject {
                    put("name", config.name)
                    put("type", config.type.displayName)
                }
            }
        }
        val result = buildJsonObject {
            put("configurations", JsonArray(configs))
        }
        CallToolResult(content = listOf(TextContent(text = result.toString())))
    }

    // --- start_debug_session ---
    server.addTool(
        name = "start_debug_session",
        description = "Launch a run configuration in debug mode. Returns session info.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("configName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Name of the run configuration to debug"))
                })
            },
            required = listOf("configName")
        )
    ) { request ->
        val configName = request.arguments?.get("configName")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing configName"))))

        try {
            // Use a deferred to get notified when the process starts or fails
            val processStarted = CompletableDeferred<Boolean>()
            val messageBus = project.messageBus.connect()

            messageBus.subscribe(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    processStarted.complete(true)
                }
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                    processStarted.complete(false)
                }
                override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                    // If terminated before we got a session, mark as failed
                    processStarted.complete(false)
                }
            })

            writeOnEdt {
                val runManager = RunManager.getInstance(project)
                val settings = runManager.allSettings.find { it.name == configName }
                    ?: throw IllegalArgumentException("Run configuration '$configName' not found")

                ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
            }

            // Wait up to 120s for the process to start (covers compilation + launch)
            val started = withTimeoutOrNull(120_000L) { processStarted.await() } ?: false
            messageBus.disconnect()

            if (started) {
                // Process started — poll briefly for debug session to register
                var session: com.intellij.xdebugger.XDebugSession? = null
                repeat(20) {
                    session = readAction { getActiveDebugSession(project) }
                    if (session != null) return@repeat
                    kotlinx.coroutines.delay(500)
                }
                val result = if (session != null) {
                    buildJsonObject {
                        put("status", JsonPrimitive("debug_session_started"))
                        put("configName", JsonPrimitive(configName))
                        put("sessionName", JsonPrimitive(session!!.sessionName))
                    }
                } else {
                    buildJsonObject {
                        put("status", JsonPrimitive("process_started"))
                        put("configName", JsonPrimitive(configName))
                        put("message", JsonPrimitive("Process is running but debug session not yet registered — poll get_debug_status"))
                    }
                }
                CallToolResult(content = listOf(TextContent(text = result.toString())))
            } else {
                val result = buildJsonObject {
                    put("status", JsonPrimitive("failed"))
                    put("configName", JsonPrimitive(configName))
                    put("message", JsonPrimitive("Debug session failed to start — check for compilation errors or missing run configuration"))
                }
                CallToolResult(content = listOf(TextContent(text = result.toString())))
            }
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to start debug session: ${e.message}"))))
        }
    }

    // --- stop_debug_session ---
    server.addTool(
        name = "stop_debug_session",
        description = "Stop a debug session. If sessionName is omitted, stops the current/first active session.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name to stop (from get_debug_status)"))
                })
            },
            required = listOf()
        )
    ) { request ->
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull
        try {
            val stoppedName = writeOnEdt {
                val session = getActiveDebugSession(project, sessionName)
                if (session != null) {
                    val name = session.sessionName
                    session.stop()
                    name
                } else {
                    null
                }
            }
            if (stoppedName != null) {
                val result = buildJsonObject {
                    put("status", "session_stopped")
                    put("sessionName", stoppedName)
                }
                CallToolResult(content = listOf(TextContent(text = result.toString())))
            } else {
                CallToolResult(content = listOf(TextContent(text = errorJson(
                    if (sessionName != null) "No debug session named '$sessionName'" else "No active debug session"
                ))))
            }
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to stop session: ${e.message}"))))
        }
    }

    // --- get_debug_status ---
    server.addTool(
        name = "get_debug_status",
        description = "Get debug session status. Shows all sessions if sessionName is omitted, or a specific session if provided. Also reports build/compilation status and running processes even when no debug session exists.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name to query. Omit to list all sessions."))
                })
            },
            required = listOf()
        )
    ) { request ->
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull
        try {
            val allSessions = readAction { getAllDebugSessions(project) }

            // Gather build & execution context
            val buildContext = gatherBuildContext(project)

            val status = if (allSessions.isEmpty()) {
                val isCompiling = buildContext["isCompiling"]?.jsonPrimitive?.booleanOrNull ?: false
                val hasErrors = buildContext["hasErrors"]?.jsonPrimitive?.booleanOrNull ?: false
                val hasRunningProcesses = (buildContext["runningProcesses"] as? JsonArray)?.isNotEmpty() ?: false

                buildJsonObject {
                    put("status", when {
                        isCompiling -> "compiling"
                        hasRunningProcesses && hasErrors -> "process_running_with_errors"
                        hasRunningProcesses -> "process_running"
                        hasErrors -> "compilation_failed"
                        else -> "no_session"
                    })
                    put("message", when {
                        isCompiling -> "Build/compilation is in progress. Debug session will start after successful compilation."
                        hasErrors && !hasRunningProcesses -> "Compilation errors detected. Fix errors and retry."
                        hasRunningProcesses -> "Process is running but no debug session registered yet."
                        else -> "No active debug session or build activity."
                    })
                    put("buildContext", buildContext)
                }
            } else if (sessionName != null) {
                val session = allSessions.firstOrNull { it.sessionName.equals(sessionName, ignoreCase = true) }
                if (session == null) {
                    buildJsonObject {
                        put("status", "not_found")
                        put("message", "No session named '$sessionName'")
                        put("availableSessions", JsonArray(allSessions.map { JsonPrimitive(it.sessionName) }))
                        put("buildContext", buildContext)
                    }
                } else {
                    readAction {
                        buildJsonObject {
                            // Spread session fields into top level
                            val sessionJson = sessionToJson(session)
                            sessionJson.forEach { (k, v) -> put(k, v) }
                            put("buildContext", buildContext)
                        }
                    }
                }
            } else {
                // Return all sessions
                readAction {
                    buildJsonObject {
                        put("sessionCount", allSessions.size)
                        put("sessions", JsonArray(allSessions.map { sessionToJson(it) }))
                        put("buildContext", buildContext)
                    }
                }
            }
            CallToolResult(content = listOf(TextContent(text = status.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to get debug status: ${e.message}"))))
        }
    }
}

/**
 * Gather build & execution context: compilation status, running processes, and error state.
 */
private fun gatherBuildContext(project: Project): JsonObject {
    return buildJsonObject {
        // 1. Check if compilation/build is currently active
        val isCompiling = try {
            CompilerManager.getInstance(project).isCompilationActive
        } catch (_: Exception) {
            false
        }
        put("isCompiling", isCompiling)

        // 2. Check for running execution processes (may be in pre-debug build phase)
        try {
            val descriptors = ExecutionManager.getInstance(project).getRunningDescriptors { true }
            if (descriptors.isNotEmpty()) {
                val processes = descriptors.map { desc ->
                    buildJsonObject {
                        put("name", desc.displayName ?: "unknown")
                        val handler = desc.processHandler
                        if (handler != null) {
                            put("isStarted", handler.isStartNotified)
                            put("isTerminated", handler.isProcessTerminated)
                            put("isTerminating", handler.isProcessTerminating)
                            val exitCode = handler.exitCode
                            if (exitCode != null) {
                                put("exitCode", exitCode)
                            }
                        }
                    }
                }
                put("runningProcesses", JsonArray(processes))
            }
        } catch (_: Exception) { }

        // 3. Check for compilation errors / problem files
        val hasErrors = try {
            WolfTheProblemSolver.getInstance(project).hasProblemFilesBeneath { true }
        } catch (_: Exception) {
            false
        }
        put("hasErrors", hasErrors)
    }
}

private fun sessionToJson(session: com.intellij.xdebugger.XDebugSession): JsonObject {
    val isSuspended = session.isSuspended
    val isPaused = session.isPaused
    val isStopped = session.isStopped

    return buildJsonObject {
        put("sessionName", session.sessionName)
        put("status", when {
            isStopped -> "stopped"
            isSuspended -> "suspended"
            isPaused -> "paused"
            else -> "running"
        })
        if (isSuspended) {
            val frame = session.currentStackFrame
            if (frame != null) {
                val position = frame.sourcePosition
                if (position != null) {
                    put("file", position.file.path)
                    put("line", position.line + 1)
                }
            }
        }
    }
}
