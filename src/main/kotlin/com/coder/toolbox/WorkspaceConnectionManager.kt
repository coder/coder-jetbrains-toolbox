package com.coder.toolbox

object WorkspaceConnectionManager {
    private val workspaceConnectionState = mutableMapOf<String, Boolean>()

    var shouldEstablishWorkspaceConnections = false

    fun allConnected(): Set<String> = workspaceConnectionState.filter { it.value }.map { it.key }.toSet()

    fun collectStatuses(workspaces: Set<CoderRemoteEnvironment>) {
        workspaces.forEach { register(it.id, it.isConnected()) }
    }

    private fun register(wsId: String, isConnected: Boolean) {
        workspaceConnectionState[wsId] = isConnected
    }

    fun reset() {
        workspaceConnectionState.clear()
        shouldEstablishWorkspaceConnections = false
    }
}