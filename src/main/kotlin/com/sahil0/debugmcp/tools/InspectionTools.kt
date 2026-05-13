package com.sahil0.debugmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon

private val PLACEHOLDER_PATTERN = Regex("""Collecting data|Loading|^\s*$""")

data class PresentationResult(
    val value: String,
    val type: String,
    val hasChildren: Boolean
)

/**
 * Compute the presentation of an XValue, skipping placeholder values like "Collecting data\u2026".
 * The Java debugger calls setPresentation twice: first with a placeholder, then with the real value.
 */
private fun computeXValuePresentation(xValue: XValue, timeoutSeconds: Long = 10): PresentationResult {
    val latch = CountDownLatch(1)
    var value = ""
    var type = ""
    var hasChildren = false

    // Track whether we've received a real (non-placeholder) presentation
    val received = java.util.concurrent.atomic.AtomicBoolean(false)

    xValue.computePresentation(object : XValueNode {
        override fun setPresentation(icon: Icon?, typeStr: String?, valueStr: String, hasChildrenFlag: Boolean) {
            if (received.get()) return  // Already got the real value
            if (PLACEHOLDER_PATTERN.containsMatchIn(valueStr)) {
                // Skip placeholder — the real value will arrive in a subsequent call
                return
            }
            value = valueStr
            type = typeStr ?: ""
            hasChildren = hasChildrenFlag
            received.set(true)
            latch.countDown()
        }

        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildrenFlag: Boolean) {
            if (received.get()) return
            val sb = StringBuilder()
            presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                override fun renderValue(v: String) { sb.append(v) }
                override fun renderStringValue(v: String) { sb.append('"').append(v).append('"') }
                override fun renderNumericValue(v: String) { sb.append(v) }
                override fun renderKeywordValue(v: String) { sb.append(v) }
                override fun renderValue(v: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(v) }
                override fun renderStringValue(v: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { sb.append('"').append(v).append('"') }
                override fun renderComment(comment: String) { sb.append(" // ").append(comment) }
                override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
                override fun renderError(error: String) { sb.append("ERROR: ").append(error) }
            })
            val rendered = sb.toString()
            if (PLACEHOLDER_PATTERN.containsMatchIn(rendered)) return
            value = rendered
            type = presentation.type ?: ""
            hasChildren = hasChildrenFlag
            received.set(true)
            latch.countDown()
        }

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
            // If a full-value evaluator is provided and we haven't gotten a real value yet,
            // use it to fetch the complete value (common for long strings)
            if (received.get()) return
            fullValueEvaluator.startEvaluation(object : XFullValueEvaluator.XFullValueEvaluationCallback {
                override fun evaluated(fullValue: String) {
                    if (received.get()) return
                    value = fullValue
                    received.set(true)
                    latch.countDown()
                }
                override fun evaluated(fullValue: String, font: java.awt.Font?) {
                    evaluated(fullValue)
                }
                override fun errorOccurred(errorMessage: String) {
                    // Don't overwrite — let the setPresentation value stand
                    latch.countDown()
                }
                override fun isObsolete(): Boolean = false
            })
        }

        override fun isObsolete(): Boolean = false
    }, XValuePlace.TREE)

    latch.await(timeoutSeconds, TimeUnit.SECONDS)
    return PresentationResult(value, type, hasChildren)
}

fun registerInspectionTools(server: Server, project: Project) {

    // --- get_variables ---
    server.addTool(
        name = "get_variables",
        description = "Get local variables and their values for the current stack frame",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("frameIndex", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("0-based frame index in the stack trace. Defaults to 0 (top frame)."))
                })
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name (from get_debug_status)"))
                })
            },
            required = listOf()
        )
    ) { request ->
        val frameIndex = request.arguments?.get("frameIndex")?.jsonPrimitive?.intOrNull ?: 0
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull

        try {
            val session = getActiveDebugSession(project, sessionName)
                ?: throw IllegalStateException("No active debug session")

            val suspendContext = session.suspendContext
                ?: throw IllegalStateException("Session is not suspended")

            val activeStack = suspendContext.activeExecutionStack
                ?: throw IllegalStateException("No active execution stack")

            // Get the stack frame at the specified index
            val frames = mutableListOf<com.intellij.xdebugger.frame.XStackFrame>()
            val frameLatch = CountDownLatch(1)
            invokeOnEdt {
                activeStack.computeStackFrames(0, object : com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(frameList: MutableList<out com.intellij.xdebugger.frame.XStackFrame>, last: Boolean) {
                        frames.addAll(frameList)
                        if (last) frameLatch.countDown()
                    }

                    override fun errorOccurred(errorMessage: String) {
                        frameLatch.countDown()
                    }
                })
            }
            frameLatch.await(5, TimeUnit.SECONDS)

            if (frameIndex >= frames.size) {
                throw IllegalArgumentException("Frame index $frameIndex out of range (${frames.size} frames available)")
            }

            val targetFrame = frames[frameIndex]

            // Phase 1: collect raw XValue references (callback fires on ManagerThread)
            data class RawVar(val name: String, val xValue: XValue)
            val rawVars = mutableListOf<RawVar>()
            val varLatch = CountDownLatch(1)
            invokeOnEdt {
                targetFrame.computeChildren(object : com.intellij.xdebugger.frame.XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            rawVars.add(RawVar(children.getName(i), children.getValue(i)))
                        }
                        if (last) varLatch.countDown()
                    }

                    override fun tooManyChildren(remaining: Int) {
                        varLatch.countDown()
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}

                    override fun setErrorMessage(errorMessage: String) {
                        varLatch.countDown()
                    }

                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        varLatch.countDown()
                    }

                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {
                        varLatch.countDown()
                    }

                    override fun isObsolete(): Boolean = false
                })
            }
            varLatch.await(10, TimeUnit.SECONDS)

            // Phase 2: compute presentations OUTSIDE the addChildren callback
            // so the ManagerThread is free to process each presentation command
            val variables = mutableListOf<JsonObject>()
            for (rv in rawVars) {
                val pres = computeXValuePresentation(rv.xValue, timeoutSeconds = 5)
                variables.add(buildJsonObject {
                    put("name", rv.name)
                    put("value", pres.value)
                    put("type", pres.type)
                    put("hasChildren", pres.hasChildren)
                })
            }

            val result = buildJsonObject {
                put("frameIndex", frameIndex)
                put("variables", JsonArray(variables))
                put("count", variables.size)
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to get variables: ${e.message}"))))
        }
    }

    // --- evaluate_expression ---
    server.addTool(
        name = "evaluate_expression",
        description = "Evaluate an expression in the current debug context",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("expression", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Java/Kotlin expression to evaluate"))
                })
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name (from get_debug_status)"))
                })
            },
            required = listOf("expression")
        )
    ) { request ->
        val expression = request.arguments?.get("expression")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing expression"))))
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull

        try {
            val session = getActiveDebugSession(project, sessionName)
                ?: throw IllegalStateException("No active debug session")

            val currentFrame = session.currentStackFrame
                ?: throw IllegalStateException("No current stack frame")

            val evaluator = currentFrame.evaluator
                ?: throw IllegalStateException("No evaluator available for current frame")

            // Phase 1: evaluate the expression (callback fires on ManagerThread)
            val evalLatch = CountDownLatch(1)
            var resultXValue: XValue? = null
            var evalError = ""

            invokeOnEdt {
                evaluator.evaluate(
                    expression,
                    object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(result: XValue) {
                            resultXValue = result
                            evalLatch.countDown()
                        }

                        override fun errorOccurred(errorMessage: String) {
                            evalError = errorMessage
                            evalLatch.countDown()
                        }
                    },
                    null
                )
            }
            evalLatch.await(10, TimeUnit.SECONDS)

            if (evalError.isNotEmpty()) {
                return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Evaluation error: $evalError"))))
            }

            val xValue = resultXValue
                ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Evaluation timed out"))))

            // Phase 2: compute presentation OUTSIDE the evaluated() callback
            // so the ManagerThread is free to process the presentation command
            val pres = computeXValuePresentation(xValue, timeoutSeconds = 10)

            val result = buildJsonObject {
                put("expression", expression)
                put("value", pres.value)
                put("type", pres.type)
                put("hasChildren", pres.hasChildren)
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to evaluate expression: ${e.message}"))))
        }
    }

    // --- get_console_output ---
    server.addTool(
        name = "get_console_output",
        description = "Retrieve recent console/stdout/stderr output from the debug process. Works even after the process has exited.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("maxLines", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum number of lines to return (default 50)"))
                })
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name (from get_debug_status)"))
                })
            },
            required = listOf()
        )
    ) { request ->
        val maxLines = request.arguments?.get("maxLines")?.jsonPrimitive?.intOrNull ?: 50
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull

        try {
            // Try active debug session first
            val session = getActiveDebugSession(project, sessionName)
            val consoleView = session?.consoleView

            val text = if (consoleView != null) {
                invokeOnEdt {
                    if (consoleView is com.intellij.execution.impl.ConsoleViewImpl) {
                        consoleView.text
                    } else ""
                }
            } else {
                // Fallback: find the most recent run content descriptor's console (works after JVM exit)
                invokeOnEdt {
                    val rcm = com.intellij.execution.ui.RunContentManager.getInstance(project)
                    val allDescriptors = rcm.getAllDescriptors()

                    var output = ""
                    for (desc in allDescriptors.reversed()) {
                        val ep = desc.executionConsole
                        if (ep is com.intellij.execution.impl.ConsoleViewImpl) {
                            val t = ep.text
                            if (t.isNotBlank()) {
                                output = t
                                break
                            }
                        }
                    }
                    output
                }
            }

            if (text.isBlank()) {
                val result = buildJsonObject {
                    put("output", "")
                    put("lineCount", 0)
                    put("message", if (session == null) "No active or recent debug session found" else "Console is empty")
                }
                return@addTool CallToolResult(content = listOf(TextContent(text = result.toString())))
            }

            val lines = text.lines()
            val outputLines = if (lines.size > maxLines) {
                lines.takeLast(maxLines)
            } else {
                lines
            }

            val result = buildJsonObject {
                put("output", outputLines.joinToString("\n"))
                put("lineCount", outputLines.size)
                put("truncated", lines.size > maxLines)
                if (session == null) put("source", "terminated_process")
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to get console output: ${e.message}"))))
        }
    }

    // --- send_console_input ---
    server.addTool(
        name = "send_console_input",
        description = "Send text input to the debug process's stdin (followed by a newline). " +
                "Use this to interact with CLI prompts, Spring Shell, or any console runner that reads from System.in.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The text to send to stdin (a newline will be appended automatically)"))
                })
                put("sessionName", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional session name (from get_debug_status)"))
                })
            },
            required = listOf("text")
        )
    ) { request ->
        val text = request.arguments?.get("text")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing text"))))
        val sessionName = request.arguments?.get("sessionName")?.jsonPrimitive?.contentOrNull

        try {
            val session = getActiveDebugSession(project, sessionName)
                ?: throw IllegalStateException("No active debug session")

            val processHandler = session.debugProcess.processHandler
            val outputStream = processHandler.processInput
                ?: throw IllegalStateException("Process stdin is not available (process may have exited or doesn't support stdin)")

            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            outputStream.write(bytes)
            outputStream.flush()

            val result = buildJsonObject {
                put("status", "input_sent")
                put("text", text)
                put("bytesWritten", bytes.size)
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to send console input: ${e.message}"))))
        }
    }
}
