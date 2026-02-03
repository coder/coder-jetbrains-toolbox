package com.coder.toolbox.util

import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.ToolboxUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

class ConnectionMonitoringService(
    private val cs: CoroutineScope,
    private val ui: ToolboxUi,
    private val logger: Logger,
    private val i18n: LocalizableStringFactory
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
                cs.launch {
                    logAndShowWarning(
                        title = "Unstable connection detected",
                        warning = "Unstable connection between Coder server and workspace detected. Your active sessions may disconnect"
                    )
                }
                alreadyNotified = true
            }
        }
    }


    private suspend fun logAndShowWarning(title: String, warning: String) {
        logger.warn(warning)
        ui.showSnackbar(
            UUID.randomUUID().toString(),
            i18n.ptrl(title),
            i18n.ptrl(warning),
            i18n.ptrl("OK")
        )
    }
}