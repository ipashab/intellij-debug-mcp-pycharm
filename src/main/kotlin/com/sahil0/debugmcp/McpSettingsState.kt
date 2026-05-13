package com.sahil0.debugmcp

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "DebugMcpSettings", storages = [Storage("debugMcpSettings.xml")])
class McpSettingsState(private val project: Project) : PersistentStateComponent<McpSettingsState.State> {

    enum class PortMode { DYNAMIC, FIXED }
    enum class BindAddress { LOCALHOST, ALL_INTERFACES, CUSTOM }

    class State {
        var portMode: String = PortMode.DYNAMIC.name
        var customPort: Int = 6274
        var bindAddress: String = BindAddress.LOCALHOST.name
        var customBindAddress: String = "127.0.0.1"
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val portMode: PortMode
        get() = try { PortMode.valueOf(myState.portMode) } catch (_: Exception) { PortMode.DYNAMIC }

    val customPort: Int
        get() = myState.customPort

    val bindAddress: BindAddress
        get() = try { BindAddress.valueOf(myState.bindAddress) } catch (_: Exception) { BindAddress.LOCALHOST }

    val customBindAddress: String
        get() = myState.customBindAddress

    val resolvedBindAddress: String
        get() = when (bindAddress) {
            BindAddress.LOCALHOST -> "127.0.0.1"
            BindAddress.ALL_INTERFACES -> "0.0.0.0"
            BindAddress.CUSTOM -> customBindAddress
        }

    fun setPortMode(mode: PortMode) {
        myState.portMode = mode.name
    }

    fun setCustomPort(port: Int) {
        myState.customPort = port
    }

    fun setBindAddress(addr: BindAddress) {
        myState.bindAddress = addr.name
    }

    fun setCustomBindAddress(addr: String) {
        myState.customBindAddress = addr
    }
}
