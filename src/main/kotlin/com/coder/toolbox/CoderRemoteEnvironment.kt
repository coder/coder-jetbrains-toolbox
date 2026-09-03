package com.coder.toolbox

import com.coder.toolbox.browser.browse
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.SshCommandProcessHandle
import com.coder.toolbox.cli.WorkspaceAddress
import com.coder.toolbox.models.WorkspaceAndAgentStatus
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.models.NetworkMetrics
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.session.SessionId
import com.coder.toolbox.session.SessionIdRegistry
import com.coder.toolbox.util.OS
import com.coder.toolbox.util.waitForFalseWithTimeout
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.CoderDelimiter
import com.coder.toolbox.views.EmptyWorkspaceContentView
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val POLL_INTERVAL = 5.seconds

private fun environmentId(workspace: Workspace, agent: WorkspaceAgent?): String =
    agent?.let { "${workspace.name}.${it.name}" } ?: workspace.name

private fun OS?.displayName(): String = when (this) {
    OS.LINUX -> "Linux"
    OS.WINDOWS -> "Windows"
    OS.MAC -> "macOS"
    null -> "unknown-OS"
}

/**
 * Represents a workspace, or a workspace and agent combination when an agent is available.
 *
 * Used in the environment list view.
 */
class CoderRemoteEnvironment(
    private val context: CoderToolboxContext,
    internal var client: CoderRestClient,
    internal var cli: CoderCLIManager,
    private val workspaceRefreshTrigger: Channel<Boolean>,
    private var workspace: Workspace,
    private var agent: WorkspaceAgent?,
) : RemoteProviderEnvironment(environmentId(workspace, agent)), BeforeConnectionHook, AfterDisconnectHook {
    private var environmentStatus = WorkspaceAndAgentStatus.from(workspace, agent)

    override var name: String = environmentId(workspace, agent)
    private var isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val connectionRequest: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val state: MutableStateFlow<RemoteEnvironmentState> =
        MutableStateFlow(environmentStatus.toRemoteEnvironmentState(context))
    override val description: MutableStateFlow<EnvironmentDescription> =
        MutableStateFlow(EnvironmentDescription.General(context.i18n.pnotr(workspace.templateDisplayName)))
    override val additionalEnvironmentInformation: MutableMap<LocalizableString, String> = mutableMapOf()
    override val actionsList: MutableStateFlow<List<ActionDescription>> = MutableStateFlow(emptyList())

    private val networkMetricsMarshaller = Moshi.Builder().build().adapter(NetworkMetrics::class.java)
    private val proxyCommandHandle = SshCommandProcessHandle(context)
    private var pollJob: Job? = null

    init {
        if (context.settingsStore.shouldAutoConnect(id)) {
            context.logger.info("Auto-connect is enabled for $id, trying to establish SSH connection")
            startSshConnection()
        }
        refreshAvailableActions()
    }

    internal fun toWorkspaceAddressOrNull(): WorkspaceAddress? = agent?.let { WorkspaceAddress.from(workspace, it) }

    internal fun currentSessionId(): SessionId? =
        agent?.let { SessionIdRegistry.findSession(workspace.name, it.name) }

    private fun refreshAvailableActions() {
        val actions = mutableListOf<ActionDescription>()
        context.logger.debug("Refreshing available actions for workspace $id with status: $environmentStatus")
        if (environmentStatus.canStop() && agent != null) {
            actions.add(Action(context, "Open web terminal") {
                context.logger.debug("Launching web terminal for $id...")
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
                    .replace("\$workspaceOwner", workspace.ownerName)
                    .replace("\$workspaceName", workspace.name)
                context.logger.debug("Opening the dashboard for $id...")
                context.desktop.browse(
                    url
                ) {
                    context.ui.showErrorInfoPopup(it)
                }
            }
        )

        actions.add(Action(context, "View template") {
            context.logger.debug("Opening the template for $id...")
            context.desktop.browse(client.url.withPath("/templates/${workspace.templateName}").toString()) {
                context.ui.showErrorInfoPopup(it)
            }
        })

        if (environmentStatus.canStart()) {
            if (workspace.outdated) {
                actions.add(Action(context, "Update and start") {
                    context.logger.debug("Updating and starting $id...")
                    val build = client.updateWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                    workspaceRefreshTrigger.trySend(true)
                })
            } else {
                actions.add(Action(context, "Start") {
                    context.logger.debug("Starting $id... ")
                    context.cs
                        .launch(CoroutineName("Start Workspace Action CLI Runner") + Dispatchers.IO) {
                            cli.startWorkspace(WorkspaceAddress.from(workspace))
                            workspaceRefreshTrigger.trySend(true)
                        }
                    // cli takes 15 seconds to move the workspace in queueing/starting state
                    // while the user won't see anything happening in TBX after start is clicked
                    // During those 15 seconds we work around by forcing a `Queuing` state
                    updateStatus(WorkspaceAndAgentStatus.Queued(workspace))
                    // force refresh of the actions list (Start should no longer be available)
                    refreshAvailableActions()
                })
            }
        }
        if (environmentStatus.canStop()) {
            if (workspace.outdated) {
                actions.add(
                    Action(context, "Update and restart") {
                        context.logger.debug(currentSessionId(), "Updating and re-starting $id...")
                        val build = client.updateWorkspace(workspace)
                        update(workspace.copy(latestBuild = build), agent)
                        workspaceRefreshTrigger.trySend(true)
                    }.withCurrentSessionId(::currentSessionId)
                )
            }
            actions.add(
                Action(context, "Stop") {
                    tryStopSshConnection()
                    context.logger.debug(currentSessionId(), "Stopping $id...")
                    val build = client.stopWorkspace(workspace)
                    update(workspace.copy(latestBuild = build), agent)
                }.withCurrentSessionId(::currentSessionId)
            )
        }
        actions.add(CoderDelimiter(context.i18n.pnotr("")))
        actions.add(
            Action(context, "Delete workspace", highlightInRed = true) {
                var dialogText =
                    if (environmentStatus.canStop()) "This will close the workspace and remove all its information, including files, unsaved changes, history, and usage data."
                    else "This will remove all information from the workspace, including files, unsaved changes, history, and usage data."
                dialogText += "\n\nType \"${workspace.name}\" below to confirm:"

                val confirmation = context.ui.showTextInputPopup(
                    if (environmentStatus.canStop()) context.i18n.ptrl("Delete running workspace?")
                    else context.i18n.ptrl("Delete workspace?"),
                    context.i18n.pnotr(dialogText),
                    context.i18n.ptrl("Workspace name"),
                    TextType.General,
                    context.i18n.ptrl("OK"),
                    context.i18n.ptrl("Cancel")
                )
                if (confirmation == workspace.name) {
                    context.logger.debug(currentSessionId(), "Deleting $id...")
                    deleteWorkspace()
                }
            }.withCurrentSessionId(::currentSessionId)
        )

        actionsList.update {
            actions
        }
    }

    private suspend fun tryStopSshConnection() {
        if (isConnected.value) {
            connectionRequest.update {
                false
            }

            if (isConnected.waitForFalseWithTimeout(10.seconds) == null) {
                val message =
                    "The SSH connection to workspace $name could not be dropped in time, " +
                            "going to stop the workspace while the SSH connection is live"
                context.logger.warn(currentSessionId(), message)
            }
        }
    }

    override fun getBeforeConnectionHooks(): List<BeforeConnectionHook> = listOf(this)

    override fun getAfterDisconnectHooks(): List<AfterDisconnectHook> = listOf(this)

    override fun beforeConnection() {
        val currentAgent = agent ?: return
        val sessionId = SessionIdRegistry.startSession(context, workspace.name, currentAgent.name)
        context.logger.info(
            sessionId,
            "Launching SSH connection to $id on a ${currentAgent.operatingSystem.displayName()} machine"
        )
        isConnected.update { true }
        context.settingsStore.updateAutoConnect(this.id, true)
        // Toolbox can invoke this hook again while retrying without first reporting a disconnect.
        // Replace the previous poller so one environment never leaves multiple pollers behind.
        pollJob?.cancel()
        pollJob = pollNetworkMetrics()
    }

    private fun pollNetworkMetrics(): Job =
        context.cs.launch(CoroutineName("Network Metrics Poller")) {
            context.logger.info(currentSessionId(), "Starting the network metrics poll job for $id")
            while (isActive) {
                val currentAgent = agent ?: break
                val sessionId = currentSessionId()
                context.logger.debug(sessionId, "Searching SSH command's PID for workspace $id...")
                val pid = proxyCommandHandle.findByWorkspaceAndAgent(workspace, currentAgent)
                if (pid == null) {
                    context.logger.debug(sessionId, "No SSH command PID was found for workspace $id")
                    delay(POLL_INTERVAL)
                    continue
                }

                val metricsFile = Path.of(context.settingsStore.networkInfoDir, "$pid.json").toFile()
                if (metricsFile.doesNotExists()) {
                    context.logger.debug(sessionId, "No metrics file found at ${metricsFile.absolutePath} for $id")
                    delay(POLL_INTERVAL)
                    continue
                }
                context.logger.debug(sessionId, "Loading metrics from ${metricsFile.absolutePath} for $id")
                try {
                    val metrics = networkMetricsMarshaller.fromJson(metricsFile.readText()) ?: return@launch
                    context.logger.debug(sessionId, "$id metrics: $metrics")
                    additionalEnvironmentInformation[context.i18n.ptrl("Network Status")] = metrics.toPretty()
                } catch (e: Exception) {
                    context.logger.error(
                        sessionId,
                        e,
                        "Error encountered while trying to load network metrics from ${metricsFile.absolutePath} for $id"
                    )
                }
                delay(POLL_INTERVAL)
            }
        }

    private fun File.doesNotExists(): Boolean = !this.exists()

    /**
     * Cancels background work when the provider drops this environment from its
     * list (e.g. a running workspace stopped and is replaced by a workspace-only
     * environment with a different id).
     *
     * Toolbox reacts to the removal by closing its environment wrapper, which only
     * cancels the wrapper's own coroutine scope and never invokes the
     * [AfterDisconnectHook], so the network metrics poller must be stopped and the session
     * registry entry must be removed here.
     */
    fun dispose() {
        pollJob?.cancel()
        pollJob = null
        isConnected.update { false }
        val removedSessionId = agent?.let {
            SessionIdRegistry.removeSession(workspace.name, it.name)
        }
        removedSessionId?.let { sessionId ->
            context.logger.info(sessionId, "Removed Toolbox SSH session for $id")
        }
    }

    override fun afterDisconnect(isManual: Boolean) {
        val sessionId = currentSessionId()
        // A false value also covers Toolbox's Reconnect action. The current Coder state is useful
        // context, but it cannot establish the disconnect cause on its own.
        val disconnectKind =
            if (isManual) "after an explicit user disconnect"
            else "without an explicit user disconnect"
        val stateInference =
            if (!isManual && !environmentStatus.ready()) {
                " The latest state may indicate a workspace or agent change."
            } else {
                ""
            }
        val latestCoderState =
            "environment=${environmentStatus.label}, " +
                    "workspace=${workspace.latestBuild.status}, " +
                    "agent=${agent?.status ?: "unavailable"}, " +
                    "agentLifecycle=${agent?.lifecycleState ?: "unavailable"}"
        context.logger.info(
            sessionId,
            "Toolbox is disconnecting SSH from $id $disconnectKind.$stateInference " +
                    "Latest known Coder state: $latestCoderState",
        )
        context.logger.info(sessionId, "Stopping the network metrics poll job for $id")
        pollJob?.cancel()
        pollJob = null
        connectionRequest.update { false }
        isConnected.update { false }
        if (isManual) {
            // if the user manually disconnects the ssh connection we should not connect automatically
            context.settingsStore.updateAutoConnect(this.id, false)
            agent?.let {
                SessionIdRegistry.removeSession(workspace.name, it.name)
            }?.let {
                context.logger.info(it, "Removed Toolbox SSH session for $id after manual disconnect")
            }
        }
    }

    /**
     * Update the workspace/agent status to the listeners, if it has changed.
     */
    fun update(newWorkspace: Workspace, newAgent: WorkspaceAgent?) {
        if (workspace.latestBuild == newWorkspace.latestBuild) {
            return
        }

        // workspace&agent status can be different from "environment status"
        // which is forced to queued state when a workspace is scheduled to start
        updateStatus(WorkspaceAndAgentStatus.from(newWorkspace, newAgent), newAgent)
        if (newAgent != null) {
            context.connectionMonitoringService.checkConnectionStatus(newWorkspace, newAgent)
        }

        // we have to regenerate the action list in order to force a redraw
        // because the actions don't have a state flow on the enabled property
        refreshAvailableActions()
    }


    private fun updateStatus(
        newState: WorkspaceAndAgentStatus,
        newAgent: WorkspaceAgent? = agent,
    ) {
        val previousEnvironmentStatus = environmentStatus
        val previousWorkspace = workspace
        val previousAgent = agent
        val sessionId = currentSessionId()

        environmentStatus = newState
        workspace = newState.workspace
        agent = newAgent
        name = environmentId(workspace, agent)
        state.update {
            environmentStatus.toRemoteEnvironmentState(context)
        }
        val message =
            "Overall status for workspace $id changed from ${previousEnvironmentStatus.label} " +
                    "to ${environmentStatus.label}. " +
                    "Workspace status: ${previousWorkspace.latestBuild.status} -> ${workspace.latestBuild.status}, " +
                    "agent status: ${previousAgent?.status} -> ${agent?.status}, " +
                    "agent lifecycle state: ${previousAgent?.lifecycleState} -> ${agent?.lifecycleState}, " +
                    "login before ready: ${previousAgent?.loginBeforeReady} -> ${agent?.loginBeforeReady}"
        context.logger.info(sessionId, message)
    }

    /**
     * The contents are provided by the SSH view provided by Toolbox, all we
     * have to do is provide it a host name. Workspaces without a resolved agent
     * have no host to connect to yet, so they get an empty contents view instead.
     */
    override suspend fun getContentsView(): EnvironmentContentsView {
        val envAgent = agent ?: run {
            context.logger.info("No agent is available for $id yet, providing an empty contents view")
            return EmptyWorkspaceContentView
        }
        return EnvironmentView(
            context,
            client.url,
            cli,
            workspace,
            envAgent,
        )
    }

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
     * Schedules the SSH connection to start as soon as possible if the workspace is ready and
     * there is no connection already established.
     *
     * The session is created or reactivated by [beforeConnection], when Toolbox confirms that it
     * is starting the connection.
     */
    fun startSshConnection() {
        if (agent == null) return
        if (environmentStatus.ready() && !isConnected.value) {
            connectionRequest.update {
                true
            }
        }
    }

    override val deleteActionFlow: StateFlow<(() -> Unit)?> = MutableStateFlow(null)

    suspend fun deleteWorkspace() {
        try {
            client.removeWorkspace(workspace)
            // mark the env as deleting otherwise we will have to
            // wait for the poller to update the status in the next 5 seconds
            state.update {
                WorkspaceAndAgentStatus.Deleting(workspace).toRemoteEnvironmentState(context)
            }

            context.cs.launch(CoroutineName("Workspace Deletion Poller")) {
                withTimeout(5.minutes) {
                    var workspaceStillExists = true
                    while (context.cs.isActive && workspaceStillExists) {
                        if (environmentStatus is WorkspaceAndAgentStatus.Deleting || environmentStatus is WorkspaceAndAgentStatus.Deleted) {
                            workspaceStillExists = false
                            context.envPageManager.showPluginEnvironmentsPage(false)
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

    /**
     * Update the client and CLI manager for this environment.
     */
    fun updateClientAndCli(client: CoderRestClient, cli: CoderCLIManager) {
        this.client = client
        this.cli = cli
    }
}
