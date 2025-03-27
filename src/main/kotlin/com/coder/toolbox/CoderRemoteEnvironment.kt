package com.coder.toolbox

import com.coder.toolbox.browser.BrowserUtil
import com.coder.toolbox.models.WorkspaceAndAgentStatus
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.EnvironmentView
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
    private var workspace: Workspace,
    private var agent: WorkspaceAgent,
) : RemoteProviderEnvironment("${workspace.name}.${agent.name}") {
    private var wsRawStatus = WorkspaceAndAgentStatus.from(workspace, agent)

    override var name: String = "${workspace.name}.${agent.name}"
    override val state: MutableStateFlow<RemoteEnvironmentState> =
        MutableStateFlow(wsRawStatus.toRemoteEnvironmentState(context))
    override val description: MutableStateFlow<EnvironmentDescription> =
        MutableStateFlow(EnvironmentDescription.General(context.i18n.pnotr(workspace.templateDisplayName)))

    override val actionsList: MutableStateFlow<List<ActionDescription>> = MutableStateFlow(getAvailableActions())

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
                    val build = client.updateWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                })
            } else {
                actions.add(Action(context.i18n.ptrl("Start")) {
                    val build = client.startWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                })
            }
        }
        if (wsRawStatus.canStop()) {
            if (workspace.outdated) {
                actions.add(Action(context.i18n.ptrl("Update and restart")) {
                    val build = client.updateWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                })
            } else {
                actions.add(Action(context.i18n.ptrl("Stop")) {
                    val build = client.stopWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                })
            }
        }
        return actions
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
        workspace,
        agent
    )

    override val connectionRequest: MutableStateFlow<Boolean>? = MutableStateFlow(false)

    /**
     * Does nothing.  In theory, we could do something like start the workspace
     * when you click into the workspace, but you would still need to press
     * "connect" anyway before the content is populated so there does not seem
     * to be much value.
     */
    override fun setVisible(visibilityState: EnvironmentVisibilityState) {
        if (wsRawStatus.ready() && visibilityState.contentsVisible == true && visibilityState.isBackendConnected == false) {
            context.logger.info("Connecting to $id...")
            context.cs.launch {
                connectionRequest?.update {
                    true
                }
            }
        }
    }

    override fun onDelete() {
        context.cs.launch {
            val shouldDelete = if (wsRawStatus.canStop()) {
                context.ui.showOkCancelPopup(
                    context.i18n.ptrl("Delete running workspace?"),
                    context.i18n.ptrl("Workspace will be closed and all the information in this workspace will be lost, including all files, unsaved changes and historical."),
                    context.i18n.ptrl("Delete"),
                    context.i18n.ptrl("Cancel")
                )
            } else {
                context.ui.showOkCancelPopup(
                    context.i18n.ptrl("Delete workspace?"),
                    context.i18n.ptrl("All the information in this workspace will be lost, including all files, unsaved changes and historical."),
                    context.i18n.ptrl("Delete"),
                    context.i18n.ptrl("Cancel")
                )
            }
            if (shouldDelete) {
                try {
                    client.removeWorkspace(workspace)
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
    }

    /**
     * An environment is equal if it has the same ID.
     */
    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (this === other) return true // Note the triple ===
        if (other !is CoderRemoteEnvironment) return false
        if (id != other.id) return false
        return true
    }

    /**
     * Companion to equals, for sets.
     */
    override fun hashCode(): Int = id.hashCode()
}
