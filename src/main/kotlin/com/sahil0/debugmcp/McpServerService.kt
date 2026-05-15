package com.sahil0.debugmcp

import com.sahil0.debugmcp.mcp.McpServer
import com.sahil0.debugmcp.mcp.runMcpSession
import com.sahil0.debugmcp.tools.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class McpServerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(McpServerService::class.java)

    // Use a dedicated thread pool with the plugin's classloader to avoid
    // ServiceLoader conflicts with IntelliJ's CoroutineExceptionHandler.
    // The DefaultDispatcher's threads use a different context classloader,
    // causing "not a subtype" errors when coroutine exception handling
    // tries ServiceLoader.load(CoroutineExceptionHandler::class.java).
    private val threadCounter = AtomicInteger(0)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "DebugMCP-worker-${threadCounter.getAndIncrement()}").also {
            it.isDaemon = true
            it.contextClassLoader = McpServerService::class.java.classLoader
        }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error("Uncaught coroutine exception in MCP server", throwable)
        lastError = "${throwable.javaClass.simpleName}: ${throwable.message}"
        isRunning = false
        notifyStatusChanged()
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)

    private var serverSocket: ServerSocket? = null
    private val activeConnections = CopyOnWriteArrayList<Job>()

    var port: Int = 0
        private set
    var isRunning: Boolean = false
        private set
    var lastError: String? = null
        private set

    val connectedClients: Int get() = activeConnections.count { it.isActive }

    // Listeners for status changes
    private val statusListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addStatusListener(listener: () -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: () -> Unit) {
        statusListeners.remove(listener)
    }

    private fun notifyStatusChanged() {
        statusListeners.forEach { it() }
    }

    fun start() {
        if (isRunning) {
            log.info("MCP Server already running on port $port")
            return
        }

        val settings = project.service<McpSettingsState>()
        val bindPort = when (settings.portMode) {
            McpSettingsState.PortMode.DYNAMIC -> resolveDynamicPort()
            McpSettingsState.PortMode.FIXED -> settings.customPort
        }

        val bindAddr = settings.resolvedBindAddress

        scope.launch {
            try {
                serverSocket = tryBind(bindPort, bindAddr)
                port = serverSocket!!.localPort
                isRunning = true
                lastError = null
                log.info("Debug MCP Server started on port $port for project '${project.name}'")

                // Write port file so external agents can discover us
                writePortFile()
                notifyStatusChanged()

                // Accept connections in a loop
                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        log.info("MCP client connected from ${clientSocket.remoteSocketAddress}")
                        handleClient(clientSocket)
                    } catch (e: java.net.SocketException) {
                        if (isRunning) log.warn("Socket accept error", e)
                        break
                    }
                }
            } catch (e: Throwable) {
                log.error("Failed to start MCP Server", e)
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                isRunning = false
                notifyStatusChanged()
            }
        }
    }

    /**
     * Resolve the port for DYNAMIC mode:
     * 1. If debugmcp.port already exists, reuse that port.
     * 2. Otherwise derive a deterministic port from the workspace path hash (range 12000–13000).
     */
    private fun resolveDynamicPort(): Int {
        // Try to reuse the port from an existing port file
        val existing = readExistingPortFile()
        if (existing != null) {
            log.info("Reusing existing port from debugmcp.port: $existing")
            return existing
        }

        // Derive a deterministic port from the workspace path hash
        val basePath = project.basePath ?: return hashPort("default")
        return hashPort(basePath)
    }

    private fun hashPort(path: String): Int {
        val hash = path.hashCode().toLong() and 0xFFFFFFFFL  // unsigned 32-bit
        return (12000 + (hash % 1000).toInt()).let { if (it < 12000) it + 1000 else it }
    }

    private fun readExistingPortFile(): Int? {
        try {
            val basePath = project.basePath ?: return null
            val portFile = File(basePath, ".idea/debugmcp.port")
            if (!portFile.exists()) return null
            val value = portFile.readText().trim().toIntOrNull()
            if (value != null && value in 1..65535) return value
        } catch (_: Exception) {}
        return null
    }

    /**
     * Try to bind to the preferred port. If it fails (already in use),
     * walk up to 20 consecutive ports in the same range before giving up.
     */
    private fun tryBind(preferredPort: Int, bindAddress: String = "127.0.0.1"): ServerSocket {
        val addr = java.net.InetAddress.getByName(bindAddress)
        for (offset in 0..20) {
            val candidate = preferredPort + offset
            if (candidate > 65535) break
            try {
                return ServerSocket(candidate, 50, addr)
            } catch (e: java.net.BindException) {
                log.info("Port $candidate in use, trying next...")
            }
        }
        // Final fallback: let the OS pick any available port
        log.warn("Could not bind to ports $preferredPort–${preferredPort + 20}, falling back to OS-assigned port")
        return ServerSocket(0, 50, addr)
    }

    private fun handleClient(socket: Socket) {
        val job = scope.launch {
            try {
                notifyStatusChanged()
                val mcpServer = createMcpServer()
                runMcpSession(socket.getInputStream(), socket.getOutputStream(), mcpServer)
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    log.warn("MCP client connection error", e)
                    lastError = "Client error: ${e.javaClass.simpleName}: ${e.message}"
                }
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {}
                notifyStatusChanged()
            }
        }
        activeConnections.add(job)
        job.invokeOnCompletion { activeConnections.remove(job) }
    }

    private fun createMcpServer(): McpServer {
        val server = McpServer()

        // Register all tool groups
        registerRunConfigTools(server, project)
        registerBreakpointTools(server, project)
        registerExecutionControlTools(server, project)
        registerInspectionTools(server, project)
        registerProcessInfoTools(server, project)
        registerClassInspectionTools(server, project)

        return server
    }

    private fun writePortFile() {
        try {
            val basePath = project.basePath ?: return
            val ideaDir = File(basePath, ".idea")
            if (!ideaDir.exists()) ideaDir.mkdirs()
            val portFile = File(ideaDir, "debugmcp.port")
            portFile.writeText(port.toString())
            portFile.deleteOnExit()
            log.info("Port file written: ${portFile.absolutePath}")
        } catch (e: Exception) {
            log.warn("Failed to write port file", e)
        }
    }

    private fun deletePortFile() {
        try {
            val basePath = project.basePath ?: return
            val portFile = File(basePath, ".idea/debugmcp.port")
            portFile.delete()
        } catch (e: Exception) {
            log.warn("Failed to delete port file", e)
        }
    }

    fun stop() {
        isRunning = false
        lastError = null
        activeConnections.forEach { it.cancel() }
        activeConnections.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        deletePortFile()
        port = 0
        log.info("Debug MCP Server stopped")
        notifyStatusChanged()
    }

    fun restart() {
        stop()
        start()
    }

    override fun dispose() {
        stop()
        scope.cancel()
        executor.shutdown()
    }
}
