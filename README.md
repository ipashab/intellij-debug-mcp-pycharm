# Debug MCP Server — IntelliJ IDEA Plugin

An IntelliJ IDEA plugin that embeds an **MCP (Model Context Protocol)** server, giving AI coding assistants full control over the Java/Kotlin debugger.

Works with **Windsurf Cascade**, **Cursor**, **Claude Code**, and any MCP-compatible client.

---

## Features

| Category | Tools |
|---|---|
| **Run Configs** | `list_run_configurations`, `start_debug_session`, `stop_debug_session`, `get_debug_status` |
| **Breakpoints** | `set_breakpoint`, `remove_breakpoint`, `list_breakpoints` |
| **Execution** | `resume`, `step_over`, `step_into`, `step_out` |
| **Inspection** | `get_threads`, `get_stack_trace`, `get_variables`, `evaluate_expression`, `get_console_output`, `send_console_input` |
| **Class Inspection** | `decompile_class` — read decompiled source from any JAR/library class |
| **Process** | `get_process_info` |

- **19 tools** total, all accessible via MCP `tools/call`.
- `set_breakpoint` / `remove_breakpoint` support `filePath` (local source) **or** `className` (JAR/library classes).
- All execution/inspection tools accept optional `sessionName` (target a specific debug session) and `port` (route to a specific IntelliJ instance).

---

## Installation

### From ZIP (recommended)

1. Build the plugin (see [Building from Source](#building-from-source)) or download a release ZIP.
2. In IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the `intellij-debug-mcp-plugin-<version>-plugin.zip` file.
4. Restart IntelliJ.

---

## Configuration

### Plugin Settings

Open the **Debug MCP** tool window (bottom bar) to configure:

| Setting | Description |
|---|---|
| **Port Mode** | `Dynamic` (auto-assigned, default) or `Fixed` (user-specified port) |
| **Fixed Port** | Port number when using Fixed mode (default: `6274`) |

Settings are persisted per-project in `.idea/debugMcpSettings.xml`.

### MCP Client Setup

The plugin includes a **bundled cross-platform launcher** (`bin/debugmcp-proxy.cmd`) that solves the boot-ordering problem: the proxy starts instantly and connects to IntelliJ on demand, so your MCP client never fails even if IntelliJ hasn't started yet.

The launcher is a polyglot shell/batch script — one file that works on **Windows**, **macOS**, and **Linux**. It finds `java` (via `JAVA_HOME` or `PATH`) and runs the proxy JAR automatically. No Python required.

The **Debug MCP** tool window shows the exact path and a ready-to-copy config snippet.

#### Windsurf / Cursor / Claude Code

Add to your MCP configuration (`mcp_config.json` or equivalent):

```json
{
  "mcpServers": {
    "intellij-debug": {
      "command": "<plugin-dir>/bin/debugmcp-proxy.cmd"
    }
  }
}
```

> **Tip:** Open the Debug MCP tool window in IntelliJ — it shows the full resolved path and a ready-to-copy config block.

#### Direct TCP Connection

For clients that support TCP/socket connections directly:

```
Host: 127.0.0.1
Port: <shown in Debug MCP tool window>
Protocol: newline-delimited JSON-RPC (MCP)
```

### Port Discovery

The plugin writes the active port to `<project>/.idea/debugmcp.port` so external tools (including the bundled proxy) can auto-discover it.

---

## How It Works

```
┌──────────────────┐       stdio        ┌──────────────────┐      TCP       ┌──────────────────┐
│   AI Assistant   │ ◄────────────────► │  stdio proxy     │ ◄────────────► │  IntelliJ Plugin │
│ (Windsurf, etc.) │   MCP JSON-RPC     │  (Kotlin, .jar)  │  JSON-RPC/TCP  │  (MCP Server)    │
└──────────────────┘                    └──────────────────┘                └──────────────────┘
```

1. The **plugin** starts a TCP MCP server inside IntelliJ (auto-start on project open).
2. The **stdio proxy** (Kotlin/JVM, launched via `debugmcp-proxy.cmd`) bridges between stdio-based MCP clients and the TCP server.
3. The proxy handles `initialize` and `tools/list` locally for instant startup, and connects to IntelliJ on demand for `tools/call`.
4. Port auto-discovered from `.idea/debugmcp.port`; reconnects automatically if IntelliJ restarts.

---

## Building from Source

### Prerequisites

- **JDK 17+**
- **Maven 3.8+**
- **IntelliJ IDEA 2026.1** installed (platform JARs referenced as system-scoped dependencies)

### Build

```bash
mvn clean package
```

Output: `target/intellij-debug-mcp-plugin-<version>-plugin.zip`

### Tests

```bash
# Unit tests (proxy logic, tool definitions, JSON-RPC parsing)
mvn test

# Integration tests (requires IntelliJ running with plugin enabled)
mvn verify
```

### IntelliJ Home

The build expects IntelliJ at the path in `pom.xml` property `intellij.home`. Override if needed:

```bash
mvn clean package -Dintelij.home="/path/to/IntelliJ IDEA"
```

---

## License

See [LICENSE](LICENSE).
