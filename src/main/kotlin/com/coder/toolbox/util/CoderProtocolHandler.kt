package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.feed.IdeFeedManager
import com.coder.toolbox.models.WorkspaceAndAgentStatus
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.withTimeout
import java.net.URL
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private const val CAN_T_HANDLE_URI_TITLE = "Can't handle URI"

@Suppress("UnstableApiUsage")
open class CoderProtocolHandler(
    private val context: CoderToolboxContext,
    ideFeedManager: IdeFeedManager,
) {
    private val settings = context.settingsStore.readOnly()

    /**
     * Given a set of URL parameters, prepare the CLI then return a workspace to
     * connect.
     *
     * Throw if required arguments are not supplied or the workspace is not in a
     * connectable state.
     */
    suspend fun handle(
        params: Map<String, String>,
        url: URL,
        restClient: CoderRestClient,
        cli: CoderCLIManager
    ) {
        val workspaceName = resolveWorkspaceName(params) ?: return
        val workspace = restClient.workspaces().matchName(workspaceName, url)
        if (workspace != null) {
            if (!prepareWorkspace(workspace, restClient, cli, url)) return
            // we resolve the agent after the workspace is started otherwise we can get misleading
            // errors like: no agent available while workspace is starting or stopping
            // we also need to retrieve the workspace again to have the latest resources (ex: agent)
            // attached to the workspace.
            val agent: WorkspaceAgent = resolveAgent(
                params,
                restClient.workspace(workspace.id)
            ) ?: return
            if (!ensureAgentIsReady(workspace, agent)) return
            delay(2.seconds)
            val environmentId = "${workspace.name}.${agent.name}"
            context.showEnvironmentPage(environmentId)

            val productCode = params.ideProductCode()
            val buildNumber = params.ideBuildNumber()
            val projectFolder = params.projectFolder()

            if (!productCode.isNullOrBlank() && !buildNumber.isNullOrBlank()) {
                launchIde(environmentId, productCode, buildNumber, projectFolder)
            }

        }
    }

    private suspend fun resolveWorkspaceName(params: Map<String, String>): String? {
        val workspace = params.workspace()
        if (workspace.isNullOrBlank()) {
            context.logAndShowError(CAN_T_HANDLE_URI_TITLE, "Query parameter \"$WORKSPACE\" is missing from URI")
            return null
        }
        return workspace
    }

    private suspend fun List<Workspace>.matchName(workspaceName: String, deploymentURL: URL): Workspace? {
        val workspace = this.firstOrNull { it.name == workspaceName }
        if (workspace == null) {
            context.logAndShowError(
                CAN_T_HANDLE_URI_TITLE,
                "There is no workspace with name $workspaceName on $deploymentURL"
            )
            return null
        }
        return workspace
    }

    private suspend fun prepareWorkspace(
        workspace: Workspace,
        restClient: CoderRestClient,
        cli: CoderCLIManager,
        url: URL
    ): Boolean {
        when (workspace.latestBuild.status) {
            WorkspaceStatus.PENDING, WorkspaceStatus.STARTING ->
                if (!restClient.waitForReady(workspace)) {
                    context.logAndShowError(
                        CAN_T_HANDLE_URI_TITLE,
                        "${workspace.name} from $url could not be ready on time"
                    )
                    return false
                }

            WorkspaceStatus.STOPPING, WorkspaceStatus.STOPPED,
            WorkspaceStatus.CANCELING, WorkspaceStatus.CANCELED -> {
                if (settings.disableAutostart) {
                    context.logAndShowWarning(
                        CAN_T_HANDLE_URI_TITLE,
                        "${workspace.name} from $url is not running and autostart is disabled"
                    )
                    return false
                }

                try {
                    if (workspace.outdated) {
                        restClient.updateWorkspace(workspace)
                    } else {
                        cli.startWorkspace(workspace.ownerName, workspace.name)
                    }
                } catch (e: Exception) {
                    context.logAndShowError(
                        CAN_T_HANDLE_URI_TITLE,
                        "${workspace.name} from $url could not be started",
                        e
                    )
                    return false
                }

                if (!restClient.waitForReady(workspace)) {
                    context.logAndShowError(
                        CAN_T_HANDLE_URI_TITLE,
                        "${workspace.name} from $url could not be started on time",
                    )
                    return false
                }
            }

            WorkspaceStatus.FAILED, WorkspaceStatus.DELETING, WorkspaceStatus.DELETED -> {
                context.logAndShowError(
                    CAN_T_HANDLE_URI_TITLE,
                    "Unable to connect to ${workspace.name} from $url"
                )
                return false
            }

            WorkspaceStatus.RUNNING -> return true // All is well
        }
        return true
    }

    private suspend fun resolveAgent(
        params: Map<String, String>,
        workspace: Workspace
    ): WorkspaceAgent? {
        try {
            return getMatchingAgent(params, workspace)
        } catch (e: IllegalArgumentException) {
            context.logAndShowError(
                CAN_T_HANDLE_URI_TITLE,
                "Can't resolve an agent for workspace ${workspace.name}",
                e
            )
            return null
        }
    }

    /**
     * Return the agent matching the provided agent ID or name in the parameters.
     *
     * @throws [IllegalArgumentException]
     */
    internal suspend fun getMatchingAgent(
        parameters: Map<String, String?>,
        workspace: Workspace,
    ): WorkspaceAgent? {
        val agents = workspace.latestBuild.resources
            .mapNotNull { it.agents }
            .flatten()

        if (agents.isEmpty()) {
            context.logAndShowError(CAN_T_HANDLE_URI_TITLE, "The workspace \"${workspace.name}\" has no agents")
            return null
        }

        // If the agent is missing and the workspace has only one, use that.
        val agent = if (!parameters.agentName().isNullOrBlank()) {
            agents.firstOrNull { it.name == parameters.agentName() }
        } else if (agents.size == 1) {
            agents.first()
        } else {
            null
        }

        if (agent == null) {
            if (!parameters.agentName().isNullOrBlank()) {
                context.logAndShowError(
                    CAN_T_HANDLE_URI_TITLE,
                    "The workspace \"${workspace.name}\" does not have an agent with name \"${parameters.agentName()}\""
                )
                return null
            } else {
                context.logAndShowError(
                    CAN_T_HANDLE_URI_TITLE,
                    "Unable to determine which agent to connect to; \"$AGENT_NAME\" must be set because the workspace \"${workspace.name}\" has more than one agent"
                )
                return null
            }
        }
        return agent
    }

    private suspend fun ensureAgentIsReady(
        workspace: Workspace,
        agent: WorkspaceAgent
    ): Boolean {
        val status = WorkspaceAndAgentStatus.from(workspace, agent)

        if (!status.ready()) {
            context.logAndShowError(
                CAN_T_HANDLE_URI_TITLE,
                "Agent ${agent.name} for workspace ${workspace.name} is not ready"
            )
            return false
        }
        return true
    }

    private fun launchIde(
        environmentId: String,
        productCode: String,
        buildNumber: String,
        projectFolder: String?
    ) {
        context.cs.launch(CoroutineName("Launch Remote IDE")) {
            val selectedIde = selectAndInstallRemoteIde(productCode, buildNumber, environmentId) ?: return@launch
            context.logger.info("$productCode-$buildNumber is already on $environmentId. Going to launch JBClient")
            installJBClient(selectedIde, environmentId).join()
            launchJBClient(selectedIde, environmentId, projectFolder)
        }
    }

    private suspend fun selectAndInstallRemoteIde(
        productCode: String,
        buildNumber: String,
        environmentId: String
    ): String? {
        val installedIdes = context.remoteIdeOrchestrator.getInstalledRemoteTools(environmentId, productCode)

        var selectedIde = "$productCode-$buildNumber"
        if (installedIdes.firstOrNull { it.contains(buildNumber) } != null) {
            context.logger.info("$selectedIde is already installed on $environmentId")
            return selectedIde
        }

        selectedIde = resolveAvailableIde(environmentId, productCode, buildNumber) ?: return null

        // needed otherwise TBX will install it again
        if (!installedIdes.contains(selectedIde)) {
            context.logger.info("Installing $selectedIde on $environmentId...")
            context.remoteIdeOrchestrator.installRemoteTool(environmentId, selectedIde)

            if (context.remoteIdeOrchestrator.waitForIdeToBeInstalled(environmentId, selectedIde)) {
                context.logger.info("Successfully installed $selectedIde on $environmentId...")
                return selectedIde
            } else {
                context.ui.showSnackbar(
                    UUID.randomUUID().toString(),
                    context.i18n.pnotr("$selectedIde could not be installed"),
                    context.i18n.pnotr("$selectedIde could not be installed on time. Check the logs for more details"),
                    context.i18n.ptrl("OK")
                )
                return null
            }
        } else {
            context.logger.info("$selectedIde is already present on $environmentId...")
            return selectedIde
        }
    }

    private suspend fun resolveAvailableIde(environmentId: String, productCode: String, buildNumber: String): String? {
        val availableVersions = context
            .remoteIdeOrchestrator
            .getAvailableRemoteTools(environmentId, productCode)

        if (availableVersions.isEmpty()) {
            context.logAndShowError(CAN_T_HANDLE_URI_TITLE, "$productCode is not available on $environmentId")
            return null
        }

        val buildNumberIsNotAvailable = availableVersions.firstOrNull { it.contains(buildNumber) } == null
        if (buildNumberIsNotAvailable) {
            val selectedIde = availableVersions.maxOf { it }
            context.logger.info("$productCode-$buildNumber is not available, we've selected the latest $selectedIde")
            return selectedIde
        }
        return "$productCode-$buildNumber"
    }

    private fun installJBClient(selectedIde: String, environmentId: String): Job =
        context.cs.launch(CoroutineName("JBClient Installer")) {
            context.logger.info("Downloading and installing JBClient counterpart to $selectedIde locally")
            context.jbClientOrchestrator.prepareClient(environmentId, selectedIde)
        }

    private fun launchJBClient(selectedIde: String, environmentId: String, projectFolder: String?) {
        context.logger.info("Launching $selectedIde on $environmentId")
        context.jbClientOrchestrator.connectToIde(environmentId, selectedIde, projectFolder)
    }

    private suspend fun CoderRestClient.waitForReady(workspace: Workspace): Boolean {
        var status = workspace.latestBuild.status
        try {
            withTimeout(2.minutes.toJavaDuration()) {
                while (status != WorkspaceStatus.RUNNING) {
                    delay(1.seconds)
                    status = this@waitForReady.workspace(workspace.id).latestBuild.status
                }
            }
            return true
        } catch (_: TimeoutCancellationException) {
            return false
        }
    }

    private suspend fun RemoteToolsHelper.waitForIdeToBeInstalled(
        environmentId: String,
        ideHint: String,
        waitTime: Duration = 2.minutes
    ): Boolean {
        var isInstalled = false
        try {
            withTimeout(waitTime.toJavaDuration()) {
                while (!isInstalled) {
                    delay(5.seconds)
                    isInstalled = getInstalledRemoteTools(environmentId, ideHint).isNotEmpty()
                }
            }
            return true
        } catch (_: TimeoutCancellationException) {
            return false
        }
    }
}

private suspend fun CoderToolboxContext.showEnvironmentPage(envId: String) {
    this.ui.showWindow()
    this.envPageManager.showEnvironmentPage(envId, false)
}