package com.sahil0.debugmcp.proxy

import kotlinx.serialization.json.*
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazy MCP Server proxy for IntelliJ Debug MCP plugin (Kotlin/JVM).
 *
 * Starts immediately as a proper MCP server (handles initialize, tools/list locally).
 * Only connects to IntelliJ on-demand when tools/call is invoked.
 * Reconnects automatically if IntelliJ restarts (port changes).
 *
 * Usage in mcp_config.json:
 * {
 *   "mcpServers": {
 *     "intellij-debug": {
 *       "command": "java",
 *       "args": ["-jar", "<plugin-dir>/lib/intellij-debug-mcp-proxy.jar"]
 *     }
 *   }
 * }
 */
object StdioProxy {

    // ── Tool definitions (served locally — no IntelliJ connection needed) ──

    internal val TOOLS: JsonArray = buildToolDefinitions()

    // ── Connection pool ──

    internal class Connection(
        val sock: Socket,
        val source: String,
        var buf: ByteArray = ByteArray(0),
        var nextId: Int = 0
    )

    internal val connections = ConcurrentHashMap<Int, Connection>()

    // ── Entry point ──

    @JvmStatic
    fun main(args: Array<String>) {
        log("IntelliJ Debug MCP proxy started (lazy mode - connects on first tool call)")
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        var line = input.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                try {
                    val msg = Json.parseToJsonElement(trimmed).jsonObject
                    handleRequest(msg)
                } catch (e: Exception) {
                    log("Error handling request: ${e.message}")
                }
            }
            line = input.readLine()
        }
    }

    // ── Request handling ──

    internal fun handleRequest(msg: JsonObject) {
        val method = msg["method"]?.jsonPrimitive?.contentOrNull ?: ""
        val msgId = msg["id"]
        val params = msg["params"]?.jsonObject ?: buildJsonObject {}

        when (method) {
            "initialize" -> sendResponse(buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", msgId!!)
                put("result", buildJsonObject {
                    put("protocolVersion", JsonPrimitive("2024-11-05"))
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", JsonPrimitive(true)) })
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", JsonPrimitive("intellij-debug-mcp-proxy"))
                        put("version", JsonPrimitive("1.0.0"))
                    })
                })
            })
            "notifications/initialized" -> { /* no response */ }
            "tools/list" -> sendResponse(buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", msgId!!)
                put("result", buildJsonObject {
                    put("tools", TOOLS)
                })
            })
            "tools/call" -> {
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val arguments = params["arguments"]?.jsonObject ?: buildJsonObject {}
                val result = forwardToolCall(toolName, arguments)
                sendResponse(buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", msgId!!)
                    put("result", result)
                })
            }
            else -> {
                if (msgId != null) {
                    sendResponse(buildJsonObject {
                        put("jsonrpc", JsonPrimitive("2.0"))
                        put("id", msgId)
                        put("error", buildJsonObject {
                            put("code", JsonPrimitive(-32601))
                            put("message", JsonPrimitive("Method not found: $method"))
                        })
                    })
                }
            }
        }
    }

    // ── stdio I/O ──

    internal fun sendResponse(obj: JsonObject) {
        val payload = Json.encodeToString(obj)
        System.out.write((payload + "\n").toByteArray(Charsets.UTF_8))
        System.out.flush()
    }

    internal fun log(msg: String) {
        System.err.println(msg)
        System.err.flush()
    }

    // ── Port discovery ──

    internal fun discoverPortFromDir(startDir: File): File? {
        var d = startDir.canonicalFile
        while (true) {
            val pf = File(d, ".idea/debugmcp.port")
            if (pf.isFile) return pf
            val parent = d.parentFile ?: break
            if (parent == d) break
            d = parent
        }
        return null
    }

    internal fun scanForPortFiles(): List<File> {
        val home = File(System.getProperty("user.home"))
        val candidates = mutableListOf<File>()
        val patterns = listOf("projects", "workspace", "work", "dev", "src", "repos")
        for (pattern in patterns) {
            val dir = File(home, pattern)
            if (dir.isDirectory) {
                dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                    val pf = File(sub, ".idea/debugmcp.port")
                    if (pf.isFile) candidates.add(pf)
                }
            }
        }
        if (candidates.isEmpty()) {
            home.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { sub ->
                val pf = File(sub, ".idea/debugmcp.port")
                if (pf.isFile) candidates.add(pf)
            }
        }
        return candidates
    }

    internal fun discoverPorts(): List<Pair<Int, String>> {
        val ports = mutableListOf<Pair<Int, String>>()
        val seen = mutableSetOf<Int>()

        // Env override
        val envPort = System.getenv("DEBUGMCP_PORT")
        if (!envPort.isNullOrBlank()) {
            for (p in envPort.split(",")) {
                val port = p.trim().toIntOrNull()
                if (port != null && port > 0 && seen.add(port)) {
                    ports.add(port to "env:DEBUGMCP_PORT")
                }
            }
            return ports
        }

        fun readPortsFromFile(pf: File) {
            try {
                for (line in pf.readText().trim().lines()) {
                    val port = line.trim().toIntOrNull()
                    if (port != null && port > 0 && seen.add(port)) {
                        ports.add(port to pf.absolutePath)
                    }
                }
            } catch (_: Exception) {}
        }

        // Project dir from env
        val projectDir = System.getenv("DEBUGMCP_PROJECT_DIR")
        if (!projectDir.isNullOrBlank()) {
            discoverPortFromDir(File(projectDir))?.let { readPortsFromFile(it) }
        }

        // CWD
        discoverPortFromDir(File(System.getProperty("user.dir")))?.let { readPortsFromFile(it) }

        // Scan common locations
        for (pf in scanForPortFiles()) {
            readPortsFromFile(pf)
        }

        return ports
    }

    // ── Connection management ──

    internal fun connectToPort(port: Int, source: String): Boolean {
        // Close stale
        connections.remove(port)?.let {
            try { it.sock.close() } catch (_: Exception) {}
        }

        val sock = try {
            Socket("127.0.0.1", port).also { it.soTimeout = 5000 }
        } catch (e: Exception) {
            log("Cannot connect to IntelliJ on port $port (from $source): ${e.message}")
            return false
        }

        val conn = Connection(sock, source)

        // MCP handshake
        return try {
            conn.nextId++
            connSend(conn, buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(conn.nextId))
                put("method", JsonPrimitive("initialize"))
                put("params", buildJsonObject {
                    put("protocolVersion", JsonPrimitive("2024-11-05"))
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject {
                        put("name", JsonPrimitive("debugmcp-proxy"))
                        put("version", JsonPrimitive("1.0.0"))
                    })
                })
            })
            val resp = connRecv(conn)
            if (resp == null) {
                log("IntelliJ on port $port did not respond to initialize")
                try { sock.close() } catch (_: Exception) {}
                return false
            }
            connSend(conn, buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive("notifications/initialized"))
            })
            Thread.sleep(200)
            connections[port] = conn
            log("Connected to IntelliJ on port $port (from: $source)")
            true
        } catch (e: Exception) {
            log("Handshake failed with IntelliJ on port $port (from $source): ${e.message}")
            try { sock.close() } catch (_: Exception) {}
            false
        }
    }

    internal fun refreshConnections() {
        val allPorts = discoverPorts()
        for ((port, source) in allPorts) {
            if (!connections.containsKey(port)) {
                connectToPort(port, source)
            }
        }
    }

    internal fun connSend(conn: Connection, obj: JsonObject) {
        val payload = Json.encodeToString(obj)
        conn.sock.getOutputStream().write((payload + "\n").toByteArray(Charsets.UTF_8))
        conn.sock.getOutputStream().flush()
    }

    internal fun connRecv(conn: Connection, timeoutMs: Int = 10000): JsonObject? {
        conn.sock.soTimeout = timeoutMs
        while (true) {
            // Check buffer for complete line
            val nlIdx = conn.buf.indexOf('\n'.code.toByte())
            if (nlIdx >= 0) {
                val line = conn.buf.copyOfRange(0, nlIdx)
                conn.buf = conn.buf.copyOfRange(nlIdx + 1, conn.buf.size)
                val trimmed = line.toString(Charsets.UTF_8).trim()
                if (trimmed.isEmpty()) continue
                try {
                    val msg = Json.parseToJsonElement(trimmed).jsonObject
                    if (msg.containsKey("id")) return msg
                    // Skip notifications
                } catch (_: Exception) {}
                continue
            }
            // Read more from socket
            val tmp = ByteArray(8192)
            val n = try {
                conn.sock.getInputStream().read(tmp)
            } catch (_: java.io.IOException) {
                return null
            }
            if (n <= 0) return null
            conn.buf = conn.buf + tmp.copyOfRange(0, n)
        }
    }

    // ── Tool call forwarding ──

    internal fun callOnConn(conn: Connection, port: Int, name: String, arguments: JsonObject): Pair<JsonObject?, Boolean> {
        return try {
            conn.nextId++
            connSend(conn, buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(conn.nextId))
                put("method", JsonPrimitive("tools/call"))
                put("params", buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("arguments", arguments)
                })
            })
            val resp = connRecv(conn, timeoutMs = 180_000)
                ?: throw java.net.SocketException("No response")
            if (resp.containsKey("error")) {
                val errResult = buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", Json.encodeToString(resp["error"]!!))
                    })})
                    put("isError", JsonPrimitive(true))
                }
                errResult to true
            } else {
                (resp["result"]?.jsonObject ?: buildJsonObject {}) to true
            }
        } catch (e: Exception) {
            log("Connection to port $port lost: ${e.message}")
            try { conn.sock.close() } catch (_: Exception) {}
            connections.remove(port)
            null to false
        }
    }

    internal fun extractTargetPort(arguments: JsonObject): Triple<Int?, JsonObject, String?> {
        val rawPort = arguments["port"]
        if (rawPort == null) return Triple(null, arguments, null)

        val cleaned = buildJsonObject {
            for ((k, v) in arguments) {
                if (k != "port") put(k, v)
            }
        }

        return try {
            val port = rawPort.jsonPrimitive.int
            if (port <= 0) throw IllegalArgumentException("port must be positive")
            Triple(port, cleaned, null)
        } catch (e: Exception) {
            Triple(null, cleaned, "Invalid proxy routing port: $rawPort")
        }
    }

    internal fun forwardToolCall(name: String, arguments: JsonObject): JsonObject {
        val (targetPort, forwardedArgs, portErr) = extractTargetPort(arguments)
        if (portErr != null) {
            return errorResult(portErr)
        }

        if (targetPort != null) {
            return forwardToSpecificPort(targetPort, name, forwardedArgs)
        }

        // Auto-discover and try all
        refreshConnections()

        if (connections.isEmpty()) {
            return errorResult("No IntelliJ instances found. No .idea/debugmcp.port file detected. Is IntelliJ running with the plugin enabled?")
        }

        for (port in connections.keys.toList()) {
            val conn = connections[port] ?: continue
            val (result, ok) = callOnConn(conn, port, name, forwardedArgs)
            if (ok && result != null) return result
        }

        // All failed — retry once
        refreshConnections()
        if (connections.isEmpty()) {
            return errorResult("Lost connection to all IntelliJ instances")
        }
        for (port in connections.keys.toList()) {
            val conn = connections[port] ?: continue
            val (result, ok) = callOnConn(conn, port, name, forwardedArgs)
            if (ok && result != null) return result
        }

        return errorResult("Lost connection to all IntelliJ instances")
    }

    private fun forwardToSpecificPort(targetPort: Int, name: String, args: JsonObject): JsonObject {
        var conn = connections[targetPort]
        if (conn == null && !connectToPort(targetPort, "args:port")) {
            return errorResult("Cannot connect to IntelliJ on requested port $targetPort")
        }
        conn = connections[targetPort]
            ?: return errorResult("Requested port $targetPort is not available")

        val (result, ok) = callOnConn(conn, targetPort, name, args)
        if (ok && result != null) return result

        // Retry once
        if (!connectToPort(targetPort, "args:port")) {
            return errorResult("Lost connection to IntelliJ on requested port $targetPort")
        }
        conn = connections[targetPort]
            ?: return errorResult("Requested port $targetPort is not available")

        val (result2, ok2) = callOnConn(conn, targetPort, name, args)
        if (ok2 && result2 != null) return result2

        return errorResult("Lost connection to IntelliJ on requested port $targetPort")
    }

    internal fun errorResult(message: String): JsonObject = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", Json.encodeToString(buildJsonObject { put("error", JsonPrimitive(message)) }))
        })})
        put("isError", JsonPrimitive(true))
    }
}
