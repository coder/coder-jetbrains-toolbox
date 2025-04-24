package com.coder.toolbox

import com.coder.toolbox.browser.BrowserUtil
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.models.WorkspaceAndAgentStatus
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.EnvironmentView
import com.jetbrains.toolbox.api.remoteDev.AfterDisconnectHook
import com.jetbrains.toolbox.api.remoteDev.BeforeConnectionHook
import com.jetbrains.toolbox.api.remoteDev.DeleteEnvironmentConfirmationParams
import com.jetbrains.toolbox.api.remoteDev.EnvironmentVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProviderEnvironment
import com.jetbrains.toolbox.api.remoteDev.environments.EnvironmentContentsView
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentDescription
import com.jetbrains.toolbox.api.remoteDev.states.RemoteEnvironmentState
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Represents an agent and workspace combination.
 *
 * Used in the environment list view.
 */
class CoderRemoteEnvironment(
    private val context: CoderToolboxContext,
    private val client: CoderRestClient,
    private val cli: CoderCLIManager,
    private var workspace: Workspace,
    private var agent: WorkspaceAgent,
) : RemoteProviderEnvironment("${workspace.name}.${agent.name}"), BeforeConnectionHook, AfterDisconnectHook {
    private var wsRawStatus = WorkspaceAndAgentStatus.from(workspace, agent)

    override var name: String = "${workspace.name}.${agent.name}"
    override val state: MutableStateFlow<RemoteEnvironmentState> =
        MutableStateFlow(wsRawStatus.toRemoteEnvironmentState(context))
    override val description: MutableStateFlow<EnvironmentDescription> =
        MutableStateFlow(EnvironmentDescription.General(context.i18n.pnotr(workspace.templateDisplayName)))

    override val actionsList: MutableStateFlow<List<ActionDescription>> = MutableStateFlow(getAvailableActions())

    fun asPairOfWorkspaceAndAgent(): Pair<Workspace, WorkspaceAgent> = Pair(workspace, agent)

    private fun getAvailableActions(): List<ActionDescription> {
        val actions = mutableListOf(
            Action(context.i18n.ptrl("Open web terminal")) {
                context.cs.launch {
                    BrowserUtil.browse(client.url.withPath("/${workspace.ownerName}/$name/terminal").toString()) {
                        context.ui.showErrorInfoPopup(it)
                    }
                }
            },
            Action(context.i18n.ptrl("Open in dashboard")) {
                context.cs.launch {
                    BrowserUtil.browse(client.url.withPath("/@${workspace.ownerName}/${workspace.name}").toString()) {
                        context.ui.showErrorInfoPopup(it)
                    }
                }
            },

            Action(context.i18n.ptrl("View template")) {
                context.cs.launch {
                    BrowserUtil.browse(client.url.withPath("/templates/${workspace.templateName}").toString()) {
                        context.ui.showErrorInfoPopup(it)
                    }
                }
            })

        if (wsRawStatus.canStart()) {
            if (workspace.outdated) {
                actions.add(Action(context.i18n.ptrl("Update and start")) {
                    context.cs.launch {
                        val build = client.updateWorkspace(workspace)
                        update(workspace.copy(latestBuild = build), agent)
                    }
                })
            } else {
                actions.add(Action(context.i18n.ptrl("Start")) {
                    context.cs.launch {
                        val build = client.startWorkspace(workspace)
                        update(workspace.copy(latestBuild = build), agent)

                    }
                })
            }
        }
        if (wsRawStatus.canStop()) {
            if (workspace.outdated) {
                actions.add(Action(context.i18n.ptrl("Update and restart")) {
                    context.cs.launch {
                        val build = client.updateWorkspace(workspace)
                        update(workspace.copy(latestBuild = build), agent)
                    }
                })
            } else {
                actions.add(Action(context.i18n.ptrl("Stop")) {
                    context.cs.launch {
                        val build = client.stopWorkspace(workspace)
                        update(workspace.copy(latestBuild = build), agent)
                    }
                })
            }
        }
        return actions
    }

    override fun getBeforeConnectionHooks(): List<BeforeConnectionHook> = listOf(this)

    override fun getAfterDisconnectHooks(): List<AfterDisconnectHook> = listOf(this)

    override fun beforeConnection() {
        context.logger.info("Connecting to $id...")
        this.isConnected = true
    }

    override fun afterDisconnect() {
        this.connectionRequest.update { false }
        this.isConnected = false
        context.logger.info("Disconnected from $id")
    }

    /**
     * Update the workspace/agent status to the listeners, if it has changed.
     */
    fun update(workspace: Workspace, agent: WorkspaceAgent) {
        this.workspace = workspace
        this.agent = agent
        wsRawStatus = WorkspaceAndAgentStatus.from(workspace, agent)
        // we have to regenerate the action list in order to force a redraw
        // because the actions don't have a state flow on the enabled property
        actionsList.update {
            getAvailableActions()
        }
        context.cs.launch {
            state.update {
                wsRawStatus.toRemoteEnvironmentState(context)
            }
        }
    }

    /**
     * The contents are provided by the SSH view provided by Toolbox, all we
     * have to do is provide it a host name.
     */
    override suspend
    fun getContentsView(): EnvironmentContentsView = EnvironmentView(
        context.settingsStore.readOnly(),
        client.url,
        cli,
        workspace,
        agent
    )

    private var isConnected = false
    override val connectionRequest: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Does nothing.  In theory, we could do something like start the workspace
     * when you click into the workspace, but you would still need to press
     * "connect" anyway before the content is populated so there does not seem
     * to be much value.
     */
    override fun setVisible(visibilityState: EnvironmentVisibilityState) {
        if (wsRawStatus.ready() && visibilityState.contentsVisible == true && isConnected == false) {
            context.cs.launch {
                connectionRequest.update {
                    true
                }
            }
        }
    }

    override fun getDeleteEnvironmentConfirmationParams(): DeleteEnvironmentConfirmationParams? {
        return object : DeleteEnvironmentConfirmationParams {
            override val cancelButtonText: String = "Cancel"
            override val confirmButtonText: String = "Delete"
            override val message: String =
                if (wsRawStatus.canStop()) "Workspace will be closed and all the information will be lost, including all files, unsaved changes, historical info and usage data."
                else "All the information in this workspace will be lost, including all files, unsaved changes, historical info and usage data."
            override val title: String = if (wsRawStatus.canStop()) "Delete running workspace?" else "Delete workspace?"
        }
    }

    override fun onDelete() {
        context.cs.launch {
            try {
                client.removeWorkspace(workspace)
                // mark the env as deleting otherwise we will have to
                // wait for the poller to update the status in the next 5 seconds
                state.update {
                    WorkspaceAndAgentStatus.DELETING.toRemoteEnvironmentState(context)
                }

                context.cs.launch {
                    withTimeout(5.minutes) {
                        var workspaceStillExists = true
                        while (context.cs.isActive && workspaceStillExists) {
                            if (wsRawStatus == WorkspaceAndAgentStatus.DELETING || wsRawStatus == WorkspaceAndAgentStatus.DELETED) {
                                workspaceStillExists = false
                                context.envPageManager.showPluginEnvironmentsPage()
                            } else {
                                delay(1.seconds)
                            }
                        }
                    }
                }
            } catch (e: APIResponseException) {
                context.ui.showErrorInfoPopup(e)
            }
        }
    }

    /**
     * An environment is equal if it has the same ID.
     */
    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (this === other) return true
        if (other !is CoderRemoteEnvironment) return false
        return id == other.id
    }

    /**
     * Companion to equals, for sets.
     */
    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String {
        return "CoderRemoteEnvironment(name='$name')"
    }
}
