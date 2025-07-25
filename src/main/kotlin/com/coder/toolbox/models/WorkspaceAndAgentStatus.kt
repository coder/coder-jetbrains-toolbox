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
enum class WorkspaceAndAgentStatus(val label: String, val description: String) {
    // Workspace states.
    QUEUED("Queued", "The workspace is queueing to start."),
    STARTING("Starting", "The workspace is starting."),
    FAILED("Failed", "The workspace has failed to start."),
    DELETING("Deleting", "The workspace is being deleted."),
    DELETED("Deleted", "The workspace has been deleted."),
    STOPPING("Stopping", "The workspace is stopping."),
    STOPPED("Stopped", "The workspace has stopped."),
    CANCELING("Canceling action", "The workspace is being canceled."),
    CANCELED("Canceled action", "The workspace has been canceled."),
    RUNNING("Running", "The workspace is running, waiting for agents."),

    // Agent states.
    CONNECTING("Connecting", "The agent is connecting."),
    DISCONNECTED("Disconnected", "The agent has disconnected."),
    TIMEOUT("Timeout", "The agent is taking longer than expected to connect."),
    AGENT_STARTING("Starting", "The startup script is running."),
    AGENT_STARTING_READY(
        "Starting",
        "The startup script is still running but the agent is ready to accept connections.",
    ),
    CREATED("Created", "The agent has been created."),
    START_ERROR("Started with error", "The agent is ready but the startup script errored."),
    START_TIMEOUT("Starting", "The startup script is taking longer than expected."),
    START_TIMEOUT_READY(
        "Starting",
        "The startup script is taking longer than expected but the agent is ready to accept connections.",
    ),
    SHUTTING_DOWN("Shutting down", "The agent is shutting down."),
    SHUTDOWN_ERROR("Shutdown with error", "The agent shut down but the shutdown script errored."),
    SHUTDOWN_TIMEOUT("Shutting down", "The shutdown script is taking longer than expected."),
    OFF("Off", "The agent has shut down."),
    READY("Ready", "The agent is ready to accept connections."),
    ;

    /**
     * Return the environment state for Toolbox, which tells it the label, color
     * and whether the environment is reachable.
     *
     * Note that a reachable environment will always display "connected" or
     * "disconnected" regardless of the label we give that status.
     */
    fun toRemoteEnvironmentState(context: CoderToolboxContext): CustomRemoteEnvironmentStateV2 {
        return CustomRemoteEnvironmentStateV2(
            context.i18n.pnotr(label),
            color = getStateColor(context),
            isReachable = ready() || unhealthy(),
            // TODO@JB: How does this work?  Would like a spinner for pending states.
            iconId = getStateIcon().id,
            isPriorityShow = true
        )
    }

    private fun getStateColor(context: CoderToolboxContext): StateColor {
        return if (this == FAILED) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.FailedToStart)
        else if (this == DELETING) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Deleting)
        else if (this == DELETED) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Deleted)
        else if (ready()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Active)
        else if (unhealthy()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Unhealthy)
        else if (canStart() || this == STOPPING) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Hibernating)
        else if (pending()) context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Activating)
        else context.envStateColorPalette.getColor(StandardRemoteEnvironmentState.Unreachable)
    }

    private fun getStateIcon(): EnvironmentStateIcons {
        return if (this == FAILED) EnvironmentStateIcons.Error
        else if (pending() || this == DELETING || this == DELETED || this == STOPPING) CircularSpinner
        else if (ready() || unhealthy()) EnvironmentStateIcons.Active
        else if (canStart()) EnvironmentStateIcons.Offline
        else EnvironmentStateIcons.NoIcon
    }

    /**
     * Return true if the agent is in a connectable state.
     */
    fun ready(): Boolean = this == READY

    fun unhealthy(): Boolean {
        return listOf(START_ERROR, START_TIMEOUT_READY)
            .contains(this)
    }

    /**
     * Return true if the agent might soon be in a connectable state.
     */
    fun pending(): Boolean {
        // See ready() for why `CREATED` is not in this list.
        return listOf(CREATED, CONNECTING, TIMEOUT, AGENT_STARTING, START_TIMEOUT, QUEUED, STARTING)
            .contains(this)
    }

    /**
     * Return true if the workspace can be started.
     */
    fun canStart(): Boolean = listOf(STOPPED, FAILED, CANCELED)
        .contains(this)

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
            WorkspaceStatus.PENDING -> QUEUED
            WorkspaceStatus.STARTING -> STARTING
            WorkspaceStatus.RUNNING ->
                when (agent?.status) {
                    WorkspaceAgentStatus.CONNECTED ->
                        when (agent.lifecycleState) {
                            WorkspaceAgentLifecycleState.CREATED -> CREATED
                            WorkspaceAgentLifecycleState.STARTING -> if (agent.loginBeforeReady == true) AGENT_STARTING_READY else AGENT_STARTING
                            WorkspaceAgentLifecycleState.START_TIMEOUT -> if (agent.loginBeforeReady == true) START_TIMEOUT_READY else START_TIMEOUT
                            WorkspaceAgentLifecycleState.START_ERROR -> START_ERROR
                            WorkspaceAgentLifecycleState.READY -> READY
                            WorkspaceAgentLifecycleState.SHUTTING_DOWN -> SHUTTING_DOWN
                            WorkspaceAgentLifecycleState.SHUTDOWN_TIMEOUT -> SHUTDOWN_TIMEOUT
                            WorkspaceAgentLifecycleState.SHUTDOWN_ERROR -> SHUTDOWN_ERROR
                            WorkspaceAgentLifecycleState.OFF -> OFF
                        }

                    WorkspaceAgentStatus.DISCONNECTED -> DISCONNECTED
                    WorkspaceAgentStatus.TIMEOUT -> TIMEOUT
                    WorkspaceAgentStatus.CONNECTING -> CONNECTING
                    else -> RUNNING
                }

            WorkspaceStatus.STOPPING -> STOPPING
            WorkspaceStatus.STOPPED -> STOPPED
            WorkspaceStatus.FAILED -> FAILED
            WorkspaceStatus.CANCELING -> CANCELING
            WorkspaceStatus.CANCELED -> CANCELED
            WorkspaceStatus.DELETING -> DELETING
            WorkspaceStatus.DELETED -> DELETED
        }
    }
}
