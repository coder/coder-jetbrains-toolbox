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

    class AgentStartingReady(workspace: Workspace) : WorkspaceAndAgentStatus("Starting", workspace)

    class Created(workspace: Workspace) : WorkspaceAndAgentStatus("Created", workspace)

    class StartError(workspace: Workspace) : WorkspaceAndAgentStatus("Started with error", workspace)

    class StartTimeout(workspace: Workspace) : WorkspaceAndAgentStatus("Starting", workspace)

    class StartTimeoutReady(workspace: Workspace) : WorkspaceAndAgentStatus("Starting", workspace)

    class ShuttingDown(workspace: Workspace) : WorkspaceAndAgentStatus("Shutting down", workspace)

    class ShutdownError(workspace: Workspace) : WorkspaceAndAgentStatus("Shutdown with error", workspace)

    class ShutdownTimeout(workspace: Workspace) : WorkspaceAndAgentStatus("Shutting down", workspace)

    class Off(workspace: Workspace) : WorkspaceAndAgentStatus("Off", workspace)

    class Ready(workspace: Workspace) : WorkspaceAndAgentStatus("Ready", workspace)

    /**
     * Return the environment state for Toolbox, which tells it the label, color
     * and whether the environment is reachable.
     *
     * Note that a reachable environment will always display "connected" or
     * "disconnected" regardless of the label we give that status.
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
        else if (ready()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Active)
        else if (unhealthy()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Unhealthy)
        else if (canStart() || this is Stopping) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Hibernating)
        else if (pending()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Activating)
        else context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Unreachable)
    }

    private fun getStateIcon(): EnvironmentStateIcons {
        return if (this is Failed) EnvironmentStateIcons.Error
        else if (pending() || this is Deleting || this is Deleted || this is Stopping) CircularSpinner
        else if (ready() || unhealthy()) EnvironmentStateIcons.Active
        else if (canStart()) EnvironmentStateIcons.Offline
        else EnvironmentStateIcons.NoIcon
    }

    /**
     * Return true if the agent is in a connectable state.
     */
    fun ready(): Boolean = this is Ready

    fun unhealthy(): Boolean {
        return this is StartError || this is StartTimeoutReady
    }

    /**
     * Return true if the agent might soon be in a connectable state.
     */
    fun pending(): Boolean {
        // See ready() for why `CREATED` is not in this list.
        return this is Created || this is Connecting || this is Timeout || this is AgentStarting || this is StartTimeout || this is Queued || this is Starting
    }

    /**
     * Return true if the workspace can be started.
     */
    fun canStart(): Boolean = this is Stopped || this is Failed || this is Canceled

    /**
     * Return true if the workspace can be stopped.
     */
    fun canStop(): Boolean = ready() || pending() || unhealthy()

    // We want to check that the workspace is `running`, the agent is
    // `connected`, and the agent lifecycle state is `ready` to ensure the best
    // possible scenario for attempting a connection.
    //
    // We can also choose to allow `start_error` for the agent lifecycle state;
    // this means the startup script did not successfully complete but the agent
    // will still accept SSH connections.
    //
    // Lastly we can also allow connections when the agent lifecycle state is
    // `starting` or `start_timeout` if `login_before_ready` is true on the
    // workspace response since this bypasses the need to wait for the script.
    //
    // Note that latest_build.status is derived from latest_build.job.status and
    // latest_build.job.transition so there is no need to check those.
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
                            WorkspaceAgentLifecycleState.STARTING -> if (agent.loginBeforeReady == true) AgentStartingReady(
                                workspace
                            ) else AgentStarting(workspace)

                            WorkspaceAgentLifecycleState.START_TIMEOUT -> if (agent.loginBeforeReady == true) StartTimeoutReady(
                                workspace
                            ) else StartTimeout(workspace)

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
