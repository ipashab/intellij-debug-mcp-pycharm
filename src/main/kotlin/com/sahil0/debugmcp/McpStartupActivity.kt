package com.sahil0.debugmcp

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class McpStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(McpStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.info("Debug MCP plugin starting for project '${project.name}'")
        val service = project.service<McpServerService>()
        service.start()
    }
}
