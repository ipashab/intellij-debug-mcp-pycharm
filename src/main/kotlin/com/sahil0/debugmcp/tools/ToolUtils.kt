package com.sahil0.debugmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.json.*

/**
 * Bridge to run code on the EDT (Event Dispatch Thread) from a coroutine context.
 * Returns the result of [block].
 */
fun <T> invokeOnEdt(block: () -> T): T {
    var result: T? = null
    var error: Throwable? = null
    ApplicationManager.getApplication().invokeAndWait {
        try {
            result = block()
        } catch (e: Throwable) {
            error = e
        }
    }
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * Bridge to run a write action on the EDT from a coroutine context.
 * Use this instead of [invokeOnEdt] when the block modifies IntelliJ model state
 * (e.g. adding/removing breakpoints, which internally modify Documents).
 */
fun <T> writeOnEdt(block: () -> T): T {
    var result: T? = null
    var error: Throwable? = null
    ApplicationManager.getApplication().invokeAndWait {
        try {
            result = ApplicationManager.getApplication().runWriteAction<T> { block() }
        } catch (e: Throwable) {
            error = e
        }
    }
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * Run a read action from a coroutine context.
 */
fun <T> readAction(block: () -> T): T {
    return ReadAction.compute<T, Throwable> { block() }
}

/**
 * Get a debug session by name, or the current/first available session if no name given.
 */
fun getActiveDebugSession(project: Project, sessionName: String? = null): XDebugSession? {
    val mgr = XDebuggerManager.getInstance(project)
    val sessions = mgr.debugSessions
    if (sessions.isEmpty()) return null

    if (sessionName != null) {
        return sessions.firstOrNull { it.sessionName.equals(sessionName, ignoreCase = true) }
    }
    // Prefer current session, fall back to first available
    return mgr.currentSession ?: sessions.firstOrNull()
}

/**
 * Get all active debug sessions.
 */
fun getAllDebugSessions(project: Project): Array<XDebugSession> {
    return XDebuggerManager.getInstance(project).debugSessions
}

/**
 * Build a JSON error response.
 */
fun errorJson(message: String): String {
    return buildJsonObject {
        put("error", message)
    }.toString()
}

/**
 * Build a JSON success response with arbitrary content.
 */
fun successJson(vararg pairs: Pair<String, JsonElement>): String {
    return buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }.toString()
}

fun jsonString(value: String): JsonElement = JsonPrimitive(value)
fun jsonInt(value: Int): JsonElement = JsonPrimitive(value)
fun jsonLong(value: Long): JsonElement = JsonPrimitive(value)
fun jsonBool(value: Boolean): JsonElement = JsonPrimitive(value)
