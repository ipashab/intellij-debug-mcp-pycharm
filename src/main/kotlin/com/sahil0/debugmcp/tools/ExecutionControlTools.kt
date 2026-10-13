package com.sahil0.debugmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.sahil0.debugmcp.mcp.McpServer
import com.sahil0.debugmcp.mcp.CallToolResult
import com.sahil0.debugmcp.mcp.TextContent
import com.sahil0.debugmcp.mcp.ToolSchema
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun registerExecutionControlTools(server: McpServer, project: Project) {

    // --- resume ---
    server.addTool(
        name = "resume",
        description = "Resume execution of the suspended debug session",
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
            invokeOnEdt {
                val session = getActiveDebugSession(project, sessionName)
                    ?: throw IllegalStateException("No active debug session")
                session.resume()
            }
            CallToolResult(content = listOf(TextContent(text = successJson("status" to jsonString("resumed")))))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to resume: ${e.message}"))))
        }
    }

    // --- step_over ---
    server.addTool(
        name = "step_over",
        description = "Step over the current line",
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
            invokeOnEdt {
                val session = getActiveDebugSession(project, sessionName)
                    ?: throw IllegalStateException("No active debug session")
                session.stepOver(false)
            }
            CallToolResult(content = listOf(TextContent(text = successJson("status" to jsonString("stepped_over")))))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to step over: ${e.message}"))))
        }
    }

    // --- step_into ---
    server.addTool(
        name = "step_into",
        description = "Step into the current method call",
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
            invokeOnEdt {
                val session = getActiveDebugSession(project, sessionName)
                    ?: throw IllegalStateException("No active debug session")
                session.stepInto()
            }
            CallToolResult(content = listOf(TextContent(text = successJson("status" to jsonString("stepped_into")))))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to step into: ${e.message}"))))
        }
    }

    // --- step_out ---
    server.addTool(
        name = "step_out",
        description = "Step out of the current method",
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
            invokeOnEdt {
                val session = getActiveDebugSession(project, sessionName)
                    ?: throw IllegalStateException("No active debug session")
                session.stepOut()
            }
            CallToolResult(content = listOf(TextContent(text = successJson("status" to jsonString("stepped_out")))))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to step out: ${e.message}"))))
        }
    }

    // --- get_threads ---
    server.addTool(
        name = "get_threads",
        description = "List all threads with their status in the current debug session",
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

            val suspendContext = session.suspendContext
                ?: throw IllegalStateException("Session is not suspended")

            val threads = mutableListOf<JsonObject>()

            // Get execution stacks from suspend context
            val latch = CountDownLatch(1)
            val stacks = mutableListOf<XExecutionStack>()

            invokeOnEdt {
                suspendContext.computeExecutionStacks(object : com.intellij.xdebugger.frame.XSuspendContext.XExecutionStackContainer {
                    override fun addExecutionStack(executionStacks: MutableList<out XExecutionStack>, last: Boolean) {
                        stacks.addAll(executionStacks)
                        if (last) latch.countDown()
                    }

                    override fun errorOccurred(errorMessage: String) {
                        latch.countDown()
                    }
                })
            }
            latch.await(5, TimeUnit.SECONDS)

            // Also include the active execution stack
            val activeStack = suspendContext.activeExecutionStack
            if (activeStack != null && stacks.none { it.displayName == activeStack.displayName }) {
                stacks.add(0, activeStack)
            }

            for (stack in stacks) {
                threads.add(buildJsonObject {
                    put("name", stack.displayName)
                    put("isActive", stack == activeStack)
                })
            }

            val result = buildJsonObject {
                put("threads", JsonArray(threads))
                put("count", threads.size)
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to get threads: ${e.message}"))))
        }
    }

    // --- get_stack_trace ---
    server.addTool(
        name = "get_stack_trace",
        description = "Get stack frames for the current (or specified) thread",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("threadName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional thread name. Uses active thread if not specified."))
                })
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name (from get_debug_status)"))
                })
            },
            required = listOf()
        )
    ) { request ->
        val threadName = request.arguments?.get("threadName")?.jsonPrimitive?.contentOrNull
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull

        try {
            val session = getActiveDebugSession(project, sessionName)
                ?: throw IllegalStateException("No active debug session")

            val suspendContext = session.suspendContext
                ?: throw IllegalStateException("Session is not suspended")

            // Find the target execution stack
            val targetStack: XExecutionStack = if (threadName != null) {
                val stacks = mutableListOf<XExecutionStack>()
                val latch = CountDownLatch(1)
                invokeOnEdt {
                    suspendContext.computeExecutionStacks(object : com.intellij.xdebugger.frame.XSuspendContext.XExecutionStackContainer {
                        override fun addExecutionStack(executionStacks: MutableList<out XExecutionStack>, last: Boolean) {
                            stacks.addAll(executionStacks)
                            if (last) latch.countDown()
                        }
                        override fun errorOccurred(errorMessage: String) { latch.countDown() }
                    })
                }
                latch.await(5, TimeUnit.SECONDS)
                stacks.find { it.displayName.contains(threadName, ignoreCase = true) }
                    ?: throw IllegalArgumentException("Thread '$threadName' not found")
            } else {
                suspendContext.activeExecutionStack
                    ?: throw IllegalStateException("No active execution stack")
            }

            // Compute stack frames
            val frames = mutableListOf<XStackFrame>()
            val frameLatch = CountDownLatch(1)
            invokeOnEdt {
                targetStack.computeStackFrames(0, object : com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(frameList: MutableList<out XStackFrame>, last: Boolean) {
                        frames.addAll(frameList)
                        if (last) frameLatch.countDown()
                    }

                    override fun errorOccurred(errorMessage: String) {
                        frameLatch.countDown()
                    }
                })
            }
            frameLatch.await(5, TimeUnit.SECONDS)

            val frameJsonList = frames.mapIndexed { index, frame ->
                buildJsonObject {
                    put("index", index)
                    val position = readAction { frame.sourcePosition }
                    if (position != null) {
                        put("file", position.file.path)
                        put("line", position.line + 1)
                    }
                    frame.equalityObject?.let { put("id", it.toString()) }
                }
            }

            val result = buildJsonObject {
                put("thread", targetStack.displayName)
                put("frames", JsonArray(frameJsonList))
                put("count", frameJsonList.size)
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to get stack trace: ${e.message}"))))
        }
    }
}
