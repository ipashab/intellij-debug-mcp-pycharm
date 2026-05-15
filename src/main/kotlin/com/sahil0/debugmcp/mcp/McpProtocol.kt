package com.sahil0.debugmcp.mcp

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream

// ── MCP-compatible types (drop-in replacements for io.modelcontextprotocol.kotlin.sdk.types) ──

data class ToolSchema(
    val properties: JsonObject = buildJsonObject {},
    val required: List<String> = emptyList()
)

data class TextContent(val text: String)

data class CallToolResult(
    val content: List<TextContent>,
    val isError: Boolean = false
)

data class CallToolRequest(
    val arguments: JsonObject? = null
)

// ── Lightweight MCP Server ──

class McpServer(
    private val serverName: String = "intellij-debug-mcp",
    private val serverVersion: String = "0.2.0"
) {
    private data class ToolDef(
        val name: String,
        val description: String,
        val schema: ToolSchema?,
        val handler: suspend (CallToolRequest) -> CallToolResult
    )

    private val tools = LinkedHashMap<String, ToolDef>()

    fun addTool(
        name: String,
        description: String,
        inputSchema: ToolSchema? = null,
        handler: suspend (CallToolRequest) -> CallToolResult
    ) {
        tools[name] = ToolDef(name, description, inputSchema, handler)
    }

    internal suspend fun handleMessage(msg: JsonObject): JsonObject? {
        val method = msg["method"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = msg["id"]
        val params = msg["params"]?.jsonObject

        return when (method) {
            "initialize" -> buildResponse(id!!, buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", true) })
                })
                put("serverInfo", buildJsonObject {
                    put("name", serverName)
                    put("version", serverVersion)
                })
            })
            "notifications/initialized" -> null  // notification, no response
            "tools/list" -> buildResponse(id!!, buildJsonObject {
                put("tools", buildToolsList())
            })
            "tools/call" -> handleToolCall(id!!, params)
            "ping" -> buildResponse(id!!, buildJsonObject {})
            else -> {
                if (id != null) buildErrorResponse(id, -32601, "Method not found: $method")
                else null  // unknown notification, ignore
            }
        }
    }

    private fun buildToolsList(): JsonArray {
        return JsonArray(tools.values.map { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    if (tool.schema != null) {
                        put("properties", tool.schema.properties)
                        if (tool.schema.required.isNotEmpty()) {
                            put("required", JsonArray(tool.schema.required.map { JsonPrimitive(it) }))
                        }
                    }
                })
            }
        })
    }

    private suspend fun handleToolCall(id: JsonElement, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val arguments = params?.get("arguments")?.jsonObject
        val tool = tools[toolName]
            ?: return buildResponse(id, buildJsonObject {
                put("content", JsonArray(listOf(buildJsonObject {
                    put("type", "text")
                    put("text", """{"error":"Unknown tool: $toolName"}""")
                })))
                put("isError", true)
            })

        return try {
            val result = tool.handler(CallToolRequest(arguments))
            buildResponse(id, buildJsonObject {
                put("content", JsonArray(result.content.map { tc ->
                    buildJsonObject {
                        put("type", "text")
                        put("text", tc.text)
                    }
                }))
                if (result.isError) put("isError", true)
            })
        } catch (e: Exception) {
            val escaped = (e.message ?: "Unknown error").replace("\"", "\\\"")
            buildResponse(id, buildJsonObject {
                put("content", JsonArray(listOf(buildJsonObject {
                    put("type", "text")
                    put("text", """{"error":"$escaped"}""")
                })))
                put("isError", true)
            })
        }
    }

    private fun buildResponse(id: JsonElement, result: JsonObject): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    private fun buildErrorResponse(id: JsonElement, code: Int, message: String): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
    }
}

// ── Session runner (replaces StdioServerTransport + Server.createSession) ──

private val log = Logger.getInstance("com.sahil0.debugmcp.mcp.McpProtocol")

/**
 * Run an MCP session over newline-delimited JSON on the given streams.
 * Blocks the calling coroutine until the input stream is closed.
 */
suspend fun runMcpSession(
    inputStream: InputStream,
    outputStream: OutputStream,
    server: McpServer
) {
    val reader = inputStream.bufferedReader(Charsets.UTF_8)
    val writer = outputStream.bufferedWriter(Charsets.UTF_8)

    while (true) {
        val line = reader.readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        try {
            val msg = Json.parseToJsonElement(trimmed).jsonObject
            val response = server.handleMessage(msg) ?: continue
            val payload = Json.encodeToString(JsonObject.serializer(), response)
            synchronized(writer) {
                writer.write(payload)
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) {
            log.warn("Error processing MCP message", e)
        }
    }
}
