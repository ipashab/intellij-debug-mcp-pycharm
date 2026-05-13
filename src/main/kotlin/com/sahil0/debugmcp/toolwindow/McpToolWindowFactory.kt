package com.sahil0.debugmcp.toolwindow

import com.sahil0.debugmcp.McpServerService
import com.sahil0.debugmcp.McpSettingsState
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

class McpToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

// ── Helpers ──────────────────────────────────────────────────────────────

private fun copyableField(text: String, columns: Int = 30): JTextField {
    return JTextField(text, columns).apply {
        isEditable = false
        border = JBUI.Borders.empty(2, 4)
        background = null          // inherit parent background
        caretColor = foreground     // visible caret when selecting
    }
}

private fun sectionTitle(text: String): JBLabel {
    return JBLabel(text).apply { font = font.deriveFont(Font.BOLD, 13f) }
}

private fun labeledRow(label: String, field: JComponent): JPanel {
    return JPanel(BorderLayout(8, 0)).apply {
        val lbl = JBLabel(label).apply { preferredSize = Dimension(130, preferredSize.height) }
        add(lbl, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        alignmentX = Component.LEFT_ALIGNMENT
    }
}

// ── Main panel ───────────────────────────────────────────────────────────

class McpPanel(private val project: Project) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val service = project.service<McpServerService>()
    private val settings = project.service<McpSettingsState>()

    private val statusField  = copyableField("Initializing...")
    private val portField    = copyableField("—")
    private val clientsField = copyableField("0")

    private val portModeCombo = JComboBox(arrayOf("Dynamic", "Fixed")).apply {
        preferredSize = Dimension(120, preferredSize.height)
    }
    private val fixedPortField = JTextField(settings.customPort.toString(), 6).apply {
        border = JBUI.Borders.empty(2, 4)
    }
    private val bindAddressCombo = JComboBox(arrayOf("127.0.0.1 (localhost)", "0.0.0.0 (all interfaces)", "Custom")).apply {
        preferredSize = Dimension(180, preferredSize.height)
    }
    private val customBindField = JTextField(settings.customBindAddress, 15).apply {
        border = JBUI.Borders.empty(2, 4)
    }

    private val startButton   = JButton("Start")
    private val stopButton    = JButton("Stop")
    private val restartButton = JButton("Restart")

    private val configArea = JTextArea(6, 0).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(4)
        lineWrap = false
    }
    private val copyConfigButton = JButton("Copy Config")

    init {
        border = JBUI.Borders.empty(10)

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            // ── Server Status ──
            add(sectionTitle("Server"))
            add(Box.createVerticalStrut(6))
            add(labeledRow("Status:", statusField))
            add(Box.createVerticalStrut(4))
            add(labeledRow("Port:", portField))
            add(Box.createVerticalStrut(4))
            add(labeledRow("Connected clients:", clientsField))
            add(Box.createVerticalStrut(12))

            // ── Settings ──
            add(sectionTitle("Settings"))
            add(Box.createVerticalStrut(6))
            add(labeledRow("Port mode:", portModeCombo))
            add(Box.createVerticalStrut(4))
            add(labeledRow("Fixed port:", fixedPortField))
            add(Box.createVerticalStrut(4))
            add(labeledRow("Bind address:", bindAddressCombo))
            add(Box.createVerticalStrut(4))
            add(labeledRow("Custom address:", customBindField))
            add(Box.createVerticalStrut(8))

            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                add(startButton)
                add(stopButton)
                add(restartButton)
            }
            add(buttonPanel)
            add(Box.createVerticalStrut(16))

            // ── MCP Configuration ──
            add(sectionTitle("MCP Client Configuration"))
            add(Box.createVerticalStrut(6))
            val configScroll = JBScrollPane(configArea).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(configScroll)
            add(Box.createVerticalStrut(4))
            val copyPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                add(copyConfigButton)
            }
            add(copyPanel)
        }

        add(JBScrollPane(mainPanel), BorderLayout.CENTER)

        // ── Wiring ──
        portModeCombo.selectedIndex = if (settings.portMode == McpSettingsState.PortMode.FIXED) 1 else 0
        bindAddressCombo.selectedIndex = when (settings.bindAddress) {
            McpSettingsState.BindAddress.ALL_INTERFACES -> 1
            McpSettingsState.BindAddress.CUSTOM -> 2
            else -> 0
        }
        updateFieldStates()

        portModeCombo.addActionListener {
            val mode = if (portModeCombo.selectedIndex == 1)
                McpSettingsState.PortMode.FIXED else McpSettingsState.PortMode.DYNAMIC
            settings.setPortMode(mode)
            updateFieldStates()
        }

        bindAddressCombo.addActionListener {
            val addr = when (bindAddressCombo.selectedIndex) {
                1 -> McpSettingsState.BindAddress.ALL_INTERFACES
                2 -> McpSettingsState.BindAddress.CUSTOM
                else -> McpSettingsState.BindAddress.LOCALHOST
            }
            settings.setBindAddress(addr)
            updateFieldStates()
        }

        fixedPortField.addActionListener {
            val port = fixedPortField.text.trim().toIntOrNull()
            if (port != null && port in 1..65535) {
                settings.setCustomPort(port)
            }
        }

        customBindField.addActionListener {
            val addr = customBindField.text.trim()
            if (addr.isNotEmpty()) {
                settings.setCustomBindAddress(addr)
            }
        }

        startButton.addActionListener   { applySettings(); service.start();   refreshAll() }
        stopButton.addActionListener    { service.stop();    refreshAll() }
        restartButton.addActionListener { applySettings(); service.restart(); refreshAll() }

        copyConfigButton.addActionListener {
            val sel = StringSelection(configArea.text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
        }

        service.addStatusListener { SwingUtilities.invokeLater { refreshAll() } }
        refreshAll()
    }

    private fun applySettings() {
        val port = fixedPortField.text.trim().toIntOrNull()
        if (port != null && port in 1..65535) {
            settings.setCustomPort(port)
        }
        val addr = customBindField.text.trim()
        if (addr.isNotEmpty()) {
            settings.setCustomBindAddress(addr)
        }
    }

    private fun updateFieldStates() {
        fixedPortField.isEnabled = portModeCombo.selectedIndex == 1
        customBindField.isEnabled = bindAddressCombo.selectedIndex == 2
    }

    private fun refreshAll() {
        val running = service.isRunning
        statusField.text  = if (running) "Running" else "Stopped"
        portField.text    = if (running) service.port.toString() else "—"
        clientsField.text = service.connectedClients.toString()

        startButton.isEnabled   = !running
        stopButton.isEnabled    = running
        restartButton.isEnabled = running

        refreshConfig()
    }

    private fun refreshConfig() {
        val proxyPath = resolveProxyPath()
        val escapedPath = proxyPath.replace("\\", "\\\\")

        configArea.text = buildString {
            appendLine("// Add to your MCP client config (mcp_config.json):")
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"intellij-debug\": {")
            appendLine("      \"command\": \"$escapedPath\"")
            appendLine("    }")
            appendLine("  }")
            appendLine("}")
        }
        configArea.caretPosition = 0
    }

    private fun resolveProxyPath(): String {
        // 1) Derive from this class's own Jar location (most reliable across OSs)
        try {
            val codeSource = McpPanel::class.java.protectionDomain?.codeSource?.location
            if (codeSource != null) {
                val jarPath = java.nio.file.Paths.get(codeSource.toURI())
                // JAR lives at <plugin-dir>/lib/<name>.jar -> parent.parent = plugin dir
                val pluginDir = jarPath.parent?.parent
                if (pluginDir != null) {
                    val launcher = pluginDir.resolve("bin").resolve("debugmcp-proxy.cmd")
                    if (launcher.toFile().exists()) {
                        return launcher.toAbsolutePath().toString()
                    }
                }
            }
        } catch (_: Exception) {}


        // 2) Try the installed plugin directory
        try {
            val descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.sahil0.debugmcp"))
            if (descriptor != null) {
                val pluginDir = descriptor.pluginPath
                val launcher = pluginDir.resolve("bin").resolve("debugmcp-proxy.cmd")
                if (launcher.toFile().exists()) {
                    return launcher.toAbsolutePath().toString()
                }
            }
        } catch (_: Exception) {}

        // 3) Fallback: resolve from the project source tree (dev mode)
        try {
            val projectBase = java.nio.file.Paths.get(project.basePath ?: "")
            val devLauncher = projectBase.resolve("src/main/bin/debugmcp-proxy.cmd")
            if (devLauncher.toFile().exists()) {
                return devLauncher.toAbsolutePath().toString()
            }
        } catch (_: Exception) {}

        return "<plugin-dir>/bin/debugmcp-proxy.cmd"
    }
}
