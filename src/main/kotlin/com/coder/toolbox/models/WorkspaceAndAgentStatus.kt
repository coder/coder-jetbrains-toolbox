package com.coder.toolbox.models

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.jetbrains.toolbox.api.core.ui.color.StateColor
import com.jetbrains.toolbox.api.remoteDev.states.CustomRemoteEnvironmentStateV2
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateIcons
import com.jetbrains.toolbox.api.remoteDev.states.StandardRemoteEnvironmentState


private val CircularSpinner: EnvironmentStateIcons = EnvironmentStateIcons.Connecting

/**
 * WorkspaceAndAgentStatus represents the combined status of a single agent and
 * its workspace (or just the workspace if there are no agents).
 */
sealed class WorkspaceAndAgentStatus(
    val label: String,
    val workspace: Workspace
) {
    // Workspace states.
    class Queued(workspace: Workspace) : WorkspaceAndAgentStatus("Queued", workspace)

    class Starting(workspace: Workspace) : WorkspaceAndAgentStatus("Starting", workspace)

    class Failed(workspace: Workspace) : WorkspaceAndAgentStatus("Failed", workspace)

    class Deleting(workspace: Workspace) : WorkspaceAndAgentStatus("Deleting", workspace)

    class Deleted(workspace: Workspace) :
        WorkspaceAndAgentStatus("Deleted", workspace)

    class Stopping(workspace: Workspace) : WorkspaceAndAgentStatus("Stopping", workspace)

    class Stopped(workspace: Workspace) : WorkspaceAndAgentStatus("Stopped", workspace)

    class Canceling(workspace: Workspace) : WorkspaceAndAgentStatus("Canceling action", workspace)

    class Canceled(workspace: Workspace) : WorkspaceAndAgentStatus("Canceled action", workspace)

    class Running(workspace: Workspace) : WorkspaceAndAgentStatus("Running", workspace)

    // Agent states.
    class Connecting(workspace: Workspace) : WorkspaceAndAgentStatus("Connecting", workspace)

    class Disconnected(workspace: Workspace) : WorkspaceAndAgentStatus("Disconnected", workspace)

    class Timeout(workspace: Workspace) : WorkspaceAndAgentStatus("Timeout", workspace)

    class AgentStarting(workspace: Workspace) : WorkspaceAndAgentStatus("Starting", workspace)

    class AgentStartingConnectable(workspace: Workspace) : WorkspaceAndAgentStatus("Startup scripts running", workspace)

    class Created(workspace: Workspace) : WorkspaceAndAgentStatus("Created", workspace)

    class StartError(workspace: Workspace) : WorkspaceAndAgentStatus("Started with error", workspace)

    class StartTimeout(workspace: Workspace) : WorkspaceAndAgentStatus("Startup script timed out", workspace)

    class ShuttingDown(workspace: Workspace) : WorkspaceAndAgentStatus("Shutting down", workspace)

    class ShutdownError(workspace: Workspace) : WorkspaceAndAgentStatus("Shutdown with error", workspace)

    class ShutdownTimeout(workspace: Workspace) : WorkspaceAndAgentStatus("Shutting down", workspace)

    class Off(workspace: Workspace) : WorkspaceAndAgentStatus("Off", workspace)

    class Ready(workspace: Workspace) : WorkspaceAndAgentStatus("Ready", workspace)

    /**
     * Return the environment state for Toolbox, which tells it the label, color
     * and whether the environment is reachable.
     *
     * The priority label keeps startup progress and failures visible even
     * when Toolbox has established a connection.
     */
    fun toRemoteEnvironmentState(context: CoderToolboxContext): CustomRemoteEnvironmentStateV2 {
        return CustomRemoteEnvironmentStateV2(
            label = context.i18n.pnotr(label),
            color = getStateColor(context),
            isReachable = this.workspace.latestBuild.status == WorkspaceStatus.RUNNING,
            // TODO@JB: How does this work?  Would like a spinner for pending states.
            iconId = getStateIcon().id,
            isPriorityShow = true
        )
    }

    private fun getStateColor(context: CoderToolboxContext): StateColor {
        return if (this is Failed) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.FailedToStart)
        else if (this is Deleting) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Deleting)
        else if (this is Deleted) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Deleted)
        else if (unhealthy()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Unhealthy)
        else if (canConnect()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Active)
        else if (canStart() || this is Stopping) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Hibernating)
        else if (pending()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Activating)
        else context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Unreachable)
    }

    private fun getStateIcon(): EnvironmentStateIcons {
        return if (this is Failed) EnvironmentStateIcons.Error
        else if (pending() || this is Deleting || this is Deleted || this is Stopping) CircularSpinner
        else if (canConnect()) EnvironmentStateIcons.Active
        else if (canStart()) EnvironmentStateIcons.Offline
        else EnvironmentStateIcons.NoIcon
    }

    /**
     * Return true if the agent is in a connectable state.
     */
    fun canConnect(): Boolean =
        this is Ready || this is AgentStartingConnectable || this is StartError || this is StartTimeout

    fun unhealthy(): Boolean {
        return this is StartError || this is StartTimeout
    }

    /**
     * Return true if the agent might soon be in a connectable state.
     */
    fun pending(): Boolean {
        return this is Created || this is Connecting || this is Timeout || this is AgentStarting || this is Queued || this is Starting
    }

    /**
     * Return true if the workspace can be started.
     */
    fun canStart(): Boolean = this is Stopped || this is Failed || this is Canceled

    /**
     * Return true if the workspace can be stopped.
     */
    fun canStop(): Boolean = canConnect() || pending()

    companion object {
        fun from(
            workspace: Workspace,
            agent: WorkspaceAgent? = null,
        ) = when (workspace.latestBuild.status) {
            WorkspaceStatus.PENDING -> Queued(workspace)
            WorkspaceStatus.STARTING -> Starting(workspace)
            WorkspaceStatus.RUNNING ->
                when (agent?.status) {
                    WorkspaceAgentStatus.CONNECTED ->
                        when (agent.lifecycleState) {
                            WorkspaceAgentLifecycleState.CREATED -> Created(workspace)
                            // Like `coder ssh`, only scripts that block login delay the connection.
                            // Startup failures and timeouts still accept SSH.
                            WorkspaceAgentLifecycleState.STARTING ->
                                if (agent.scripts.any { it.runOnStart && it.startBlocksLogin }) AgentStarting(workspace)
                                else AgentStartingConnectable(workspace)

                            WorkspaceAgentLifecycleState.START_TIMEOUT -> StartTimeout(workspace)
                            WorkspaceAgentLifecycleState.START_ERROR -> StartError(workspace)
                            WorkspaceAgentLifecycleState.READY -> Ready(workspace)
                            WorkspaceAgentLifecycleState.SHUTTING_DOWN -> ShuttingDown(workspace)
                            WorkspaceAgentLifecycleState.SHUTDOWN_TIMEOUT -> ShutdownTimeout(workspace)
                            WorkspaceAgentLifecycleState.SHUTDOWN_ERROR -> ShutdownError(workspace)
                            WorkspaceAgentLifecycleState.OFF -> Off(workspace)
                        }

                    WorkspaceAgentStatus.DISCONNECTED -> Disconnected(workspace)
                    WorkspaceAgentStatus.TIMEOUT -> Timeout(workspace)
                    WorkspaceAgentStatus.CONNECTING -> Connecting(workspace)
                    else -> Running(workspace)
                }

            WorkspaceStatus.STOPPING -> Stopping(workspace)
            WorkspaceStatus.STOPPED -> Stopped(workspace)
            WorkspaceStatus.FAILED -> Failed(workspace)
            WorkspaceStatus.CANCELING -> Canceling(workspace)
            WorkspaceStatus.CANCELED -> Canceled(workspace)
            WorkspaceStatus.DELETING -> Deleting(workspace)
            WorkspaceStatus.DELETED -> Deleted(workspace)
        }
    }
}
