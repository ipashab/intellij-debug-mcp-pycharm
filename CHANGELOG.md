# Changelog

All notable changes to the **Debug MCP Server** IntelliJ plugin are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/).

---

## [0.2.0] — 2026-05-14

### Changed
- Made the plugin lighter and reduced thirdparty dependencies.

## [0.1.0] — 2025-05-10

### Added
- **19 MCP tools**: run configs, breakpoints, execution control, variable inspection, expression evaluation, console I/O, class decompilation, process info.
- TCP server with **dynamic** (auto-assigned) or **fixed** port, configurable in the Debug MCP tool window.
- Auto-start on project open; port written to `.idea/debugmcp.port` for auto-discovery.
- Bundled **Kotlin stdio proxy** (`intellij-debug-mcp-proxy.jar`) with cross-platform launcher (`bin/debugmcp-proxy.cmd`) — no Python required, works on Windows/macOS/Linux with a single config entry.
- Unit and integration test suite (JUnit 5); unit tests run with `mvn test`, integration tests with `mvn verify`.
- **Debug MCP tool window** with live status, settings, and ready-to-copy MCP configuration snippets.
- Multi-instance support: proxy can route to a specific IntelliJ via the `port` parameter.
- `set_breakpoint` / `remove_breakpoint` support `className` for JAR/library classes.
- `decompile_class` with optional line range filtering.
- `send_console_input` for interactive stdin (Spring Shell, CLI prompts).
- Two-phase variable/expression evaluation to avoid ManagerThread deadlock.
