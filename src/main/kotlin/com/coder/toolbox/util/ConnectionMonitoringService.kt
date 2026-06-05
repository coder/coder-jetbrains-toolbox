package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus

class ConnectionMonitoringService(
    private val context: CoderToolboxContext
) {
    private var alreadyNotified = false

    fun checkConnectionStatus(ws: Workspace, agent: WorkspaceAgent) {
        if (alreadyNotified) {
            return
        }

        val isWorkspaceRunning = ws.latestBuild.status == WorkspaceStatus.RUNNING
        val isAgentReady = agent.lifecycleState == WorkspaceAgentLifecycleState.READY
        val hasConnectionIssue = agent.status in setOf(
            WorkspaceAgentStatus.DISCONNECTED,
            WorkspaceAgentStatus.TIMEOUT
        )

        when {
            isWorkspaceRunning && isAgentReady && hasConnectionIssue -> {
                context.logAndShowWarning(
                    "Unstable connection detected",
                    "Unstable connection between Coder server and workspace detected. Your active sessions may disconnect"
                )
                alreadyNotified = true
            }
        }
    }
}
