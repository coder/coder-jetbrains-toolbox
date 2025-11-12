package com.coder.toolbox

import com.coder.toolbox.browser.browse
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.SshCommandProcessHandle
import com.coder.toolbox.models.WorkspaceAndAgentStatus
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.models.NetworkMetrics
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.util.waitForFalseWithTimeout
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.CoderDelimiter
import com.coder.toolbox.views.EnvironmentView
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.remoteDev.AfterDisconnectHook
import com.jetbrains.toolbox.api.remoteDev.BeforeConnectionHook
import com.jetbrains.toolbox.api.remoteDev.EnvironmentVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProviderEnvironment
import com.jetbrains.toolbox.api.remoteDev.environments.EnvironmentContentsView
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentDescription
import com.jetbrains.toolbox.api.remoteDev.states.RemoteEnvironmentState
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.components.TextType
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val POLL_INTERVAL = 5.seconds

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
    private var isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val connectionRequest: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val state: MutableStateFlow<RemoteEnvironmentState> =
        MutableStateFlow(wsRawStatus.toRemoteEnvironmentState(context))
    override val description: MutableStateFlow<EnvironmentDescription> =
        MutableStateFlow(EnvironmentDescription.General(context.i18n.pnotr(workspace.templateDisplayName)))
    override val additionalEnvironmentInformation: MutableMap<LocalizableString, String> = mutableMapOf()
    override val actionsList: MutableStateFlow<List<ActionDescription>> = MutableStateFlow(getAvailableActions())

    private val networkMetricsMarshaller = Moshi.Builder().build().adapter(NetworkMetrics::class.java)
    private val proxyCommandHandle = SshCommandProcessHandle(context)
    private var pollJob: Job? = null
    private val startIsInProgress = AtomicBoolean(false)

    init {
        if (context.settingsStore.shouldAutoConnect(id)) {
            context.logger.info("resuming SSH connection to $id — last session was still active.")
            startSshConnection()
        }
    }

    fun asPairOfWorkspaceAndAgent(): Pair<Workspace, WorkspaceAgent> = Pair(workspace, agent)

    private fun getAvailableActions(): List<ActionDescription> {
        val actions = mutableListOf<ActionDescription>()
        if (wsRawStatus.canStop()) {
            actions.add(Action(context, "Open web terminal") {
                context.desktop.browse(client.url.withPath("/${workspace.ownerName}/$name/terminal").toString()) {
                    context.ui.showErrorInfoPopup(it)
                }
            }
            )
        }
        actions.add(
            Action(context, "Open in dashboard") {
                val urlTemplate = context.settingsStore.workspaceViewUrl
                    ?: client.url.withPath("/@${workspace.ownerName}/${workspace.name}").toString()
                val url = urlTemplate
                    .replace("\$workspaceOwner", "${workspace.ownerName}")
                    .replace("\$workspaceName", workspace.name)
                context.desktop.browse(
                    url
                ) {
                    context.ui.showErrorInfoPopup(it)
                }
            }
        )

        actions.add(Action(context, "View template") {
            context.desktop.browse(client.url.withPath("/templates/${workspace.templateName}").toString()) {
                context.ui.showErrorInfoPopup(it)
            }
        }
        )

        if (wsRawStatus.canStart()) {
            if (workspace.outdated) {
                actions.add(Action(context, "Update and start") {
                    val build = client.updateWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                }
                )
            } else {
                actions.add(Action(context, "Start") {
                    try {
                        // needed in order to make sure Queuing is not overridden by the
                        // general polling loop with the `Stopped` state
                        startIsInProgress.set(true)
                        val startJob = context.cs
                            .launch(CoroutineName("Start Workspace Action CLI Runner") + Dispatchers.IO) {
                                cli.startWorkspace(workspace.ownerName, workspace.name)
                            }
                        // cli takes 15 seconds to move the workspace in queueing/starting state
                        // while the user won't see anything happening in TBX after start is clicked
                        // During those 15 seconds we work around by forcing a `Queuing` state
                        while (startJob.isActive && client.workspace(workspace.id).latestBuild.status.isNotStarted()) {
                            state.update {
                                WorkspaceAndAgentStatus.QUEUED.toRemoteEnvironmentState(context)
                            }
                            delay(1.seconds)
                        }
                        startIsInProgress.set(false)
                        // retrieve the status again and update the status
                        update(client.workspace(workspace.id), agent)
                    } finally {
                        startIsInProgress.set(false)
                    }
                }
                )
            }
        }
        if (wsRawStatus.canStop()) {
            if (workspace.outdated) {
                actions.add(Action(context, "Update and restart") {
                    val build = client.updateWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                }
                )
            }
            actions.add(Action(context, "Stop") {
                tryStopSshConnection()

                val build = client.stopWorkspace(workspace)
                update(workspace.copy(latestBuild = build), agent)
            }
            )
        }
        actions.add(CoderDelimiter(context.i18n.pnotr("")))
        actions.add(Action(context, "Delete workspace", highlightInRed = true) {
            context.cs.launch(CoroutineName("Delete Workspace Action")) {
                var dialogText =
                    if (wsRawStatus.canStop()) "This will close the workspace and remove all its information, including files, unsaved changes, history, and usage data."
                    else "This will remove all information from the workspace, including files, unsaved changes, history, and usage data."
                dialogText += "\n\nType \"${workspace.name}\" below to confirm:"

                val confirmation = context.ui.showTextInputPopup(
                    if (wsRawStatus.canStop()) context.i18n.ptrl("Delete running workspace?") else context.i18n.ptrl("Delete workspace?"),
                    context.i18n.pnotr(dialogText),
                    context.i18n.ptrl("Workspace name"),
                    TextType.General,
                    context.i18n.ptrl("OK"),
                    context.i18n.ptrl("Cancel")
                )
                if (confirmation != workspace.name) {
                    return@launch
                }
                deleteWorkspace()
            }
        })
        return actions
    }

    private suspend fun tryStopSshConnection() {
        if (isConnected.value) {
            connectionRequest.update {
                false
            }

            if (isConnected.waitForFalseWithTimeout(10.seconds) == null) {
                context.logger.warn("The SSH connection to workspace $name could not be dropped in time, going to stop the workspace while the SSH connection is live")
            }
        }
    }

    override fun getBeforeConnectionHooks(): List<BeforeConnectionHook> = listOf(this)

    override fun getAfterDisconnectHooks(): List<AfterDisconnectHook> = listOf(this)

    override fun beforeConnection() {
        context.logger.info("Connecting to $id...")
        isConnected.update { true }
        context.settingsStore.updateAutoConnect(this.id, true)
        pollJob = pollNetworkMetrics()
    }

    private fun pollNetworkMetrics(): Job = context.cs.launch(CoroutineName("Network Metrics Poller")) {
        context.logger.info("Starting the network metrics poll job for $id")
        while (isActive) {
            context.logger.debug("Searching SSH command's PID for workspace $id...")
            val pid = proxyCommandHandle.findByWorkspaceAndAgent(workspace, agent)
            if (pid == null) {
                context.logger.debug("No SSH command PID was found for workspace $id")
                delay(POLL_INTERVAL)
                continue
            }

            val metricsFile = Path.of(context.settingsStore.networkInfoDir, "$pid.json").toFile()
            if (metricsFile.doesNotExists()) {
                context.logger.debug("No metrics file found at ${metricsFile.absolutePath} for $id")
                delay(POLL_INTERVAL)
                continue
            }
            context.logger.debug("Loading metrics from ${metricsFile.absolutePath} for $id")
            try {
                val metrics = networkMetricsMarshaller.fromJson(metricsFile.readText()) ?: return@launch
                context.logger.debug("$id metrics: $metrics")
                additionalEnvironmentInformation[context.i18n.ptrl("Network Status")] = metrics.toPretty()
            } catch (e: Exception) {
                context.logger.error(
                    e,
                    "Error encountered while trying to load network metrics from ${metricsFile.absolutePath} for $id"
                )
            }
            delay(POLL_INTERVAL)
        }
    }

    private fun File.doesNotExists(): Boolean = !this.exists()

    override fun afterDisconnect(isManual: Boolean) {
        context.logger.info("Stopping the network metrics poll job for $id")
        pollJob?.cancel()
        this.connectionRequest.update { false }
        isConnected.update { false }
        if (isManual) {
            // if the user manually disconnects the ssh connection we should not connect automatically
            context.settingsStore.updateAutoConnect(this.id, false)
        }
        context.logger.info("Disconnected from $id")
    }

    /**
     * Update the workspace/agent status to the listeners, if it has changed.
     */
    fun update(workspace: Workspace, agent: WorkspaceAgent) {
        if (startIsInProgress.get()) {
            context.logger.info("Skipping update for $id - workspace start is in progress")
            return
        }
        this.workspace = workspace
        this.agent = agent
        wsRawStatus = WorkspaceAndAgentStatus.from(workspace, agent)
        // we have to regenerate the action list in order to force a redraw
        // because the actions don't have a state flow on the enabled property
        actionsList.update {
            getAvailableActions()
        }
        context.cs.launch(CoroutineName("Workspace Status Updater")) {
            state.update {
                wsRawStatus.toRemoteEnvironmentState(context)
            }
        }
    }

    /**
     * The contents are provided by the SSH view provided by Toolbox, all we
     * have to do is provide it a host name.
     */
    override suspend fun getContentsView(): EnvironmentContentsView = EnvironmentView(
        client.url,
        cli,
        workspace,
        agent
    )

    /**
     * Automatically launches the SSH connection if the workspace is visible, is ready and there is no
     * connection already established.
     */
    override fun setVisible(visibilityState: EnvironmentVisibilityState) {
        if (visibilityState.contentsVisible) {
            startSshConnection()
        }
    }

    /**
     * Launches the SSH connection if the workspace is ready and there is no connection already established.
     *
     * Returns true if the SSH connection was scheduled to start, false otherwise.
     */
    fun startSshConnection(): Boolean {
        if (wsRawStatus.ready() && !isConnected.value) {
            context.cs.launch(CoroutineName("SSH Connection Trigger")) {
                connectionRequest.update {
                    true
                }
            }
            return true
        }
        return false
    }

    override val deleteActionFlow: StateFlow<(() -> Unit)?> = MutableStateFlow(null)

    suspend fun deleteWorkspace() {
        try {
            client.removeWorkspace(workspace)
            // mark the env as deleting otherwise we will have to
            // wait for the poller to update the status in the next 5 seconds
            state.update {
                WorkspaceAndAgentStatus.DELETING.toRemoteEnvironmentState(context)
            }

            context.cs.launch(CoroutineName("Workspace Deletion Poller")) {
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

    fun isConnected(): Boolean = isConnected.value

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
