package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.models.WorkspaceAndAgentStatus
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.jetbrains.toolbox.api.localization.LocalizableString
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

open class CoderProtocolHandler(
    private val context: CoderToolboxContext,
    private val dialogUi: DialogUi,
    private val isInitialized: StateFlow<Boolean>,
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
        uri: URI,
        shouldWaitForAutoLogin: Boolean,
        reInitialize: suspend (CoderRestClient, CoderCLIManager) -> Unit
    ) {
        context.popupPluginMainPage()
        val params = uri.toQueryParameters()
        if (params.isEmpty()) {
            // probably a plugin installation scenario
            return
        }

        val deploymentURL = params.url() ?: askUrl()
        if (deploymentURL.isNullOrBlank()) {
            context.logger.error("Query parameter \"$URL\" is missing from URI $uri")
            context.showErrorPopup(MissingArgumentException("Can't handle URI because query parameter \"$URL\" is missing"))
            return
        }

        val queryToken = params.token()
        val restClient = try {
            authenticate(deploymentURL, queryToken)
        } catch (ex: Exception) {
            context.logger.error(ex, "Query parameter \"$TOKEN\" is missing from URI $uri")
            context.showErrorPopup(IllegalStateException(humanizeConnectionError(deploymentURL.toURL(), true, ex)))
            return
        }

        // TODO: Show a dropdown and ask for the workspace if missing. Right now it's not possible because dialogs are quite limited
        val workspaceName = params.workspace()
        if (workspaceName.isNullOrBlank()) {
            context.logger.error("Query parameter \"$WORKSPACE\" is missing from URI $uri")
            context.showErrorPopup(MissingArgumentException("Can't handle URI because query parameter \"$WORKSPACE\" is missing"))
            return
        }

        val workspaces = restClient.workspaces()
        val workspace = workspaces.firstOrNull { it.name == workspaceName }
        if (workspace == null) {
            context.logger.error("There is no workspace with name $workspaceName on $deploymentURL")
            context.showErrorPopup(MissingArgumentException("Can't handle URI because workspace with name $workspaceName does not exist"))
            return
        }

        when (workspace.latestBuild.status) {
            WorkspaceStatus.PENDING, WorkspaceStatus.STARTING ->
                if (restClient.waitForReady(workspace) != true) {
                    context.logger.error("$workspaceName from $deploymentURL could not be ready on time")
                    context.showErrorPopup(MissingArgumentException("Can't handle URI because workspace $workspaceName could not be ready on time"))
                    return
                }

            WorkspaceStatus.STOPPING, WorkspaceStatus.STOPPED,
            WorkspaceStatus.CANCELING, WorkspaceStatus.CANCELED -> {
                if (settings.disableAutostart) {
                    context.logger.warn("$workspaceName from $deploymentURL is not started and autostart is disabled.")
                    context.showInfoPopup(
                        context.i18n.pnotr("$workspaceName is not running"),
                        context.i18n.ptrl("Can't handle URI because workspace is not running and autostart is disabled. Please start the workspace manually and execute the URI again."),
                        context.i18n.ptrl("OK")
                    )
                    return
                }

                try {
                    restClient.startWorkspace(workspace)
                } catch (e: Exception) {
                    context.logger.error(
                        e,
                        "$workspaceName from $deploymentURL could not be started while handling URI"
                    )
                    context.showErrorPopup(MissingArgumentException("Can't handle URI because an error was encountered while trying to start workspace $workspaceName"))
                    return
                }
                if (restClient.waitForReady(workspace) != true) {
                    context.logger.error("$workspaceName from $deploymentURL could not be started on time")
                    context.showErrorPopup(MissingArgumentException("Can't handle URI because workspace $workspaceName could not be started on time"))
                    return
                }
            }

            WorkspaceStatus.FAILED, WorkspaceStatus.DELETING, WorkspaceStatus.DELETED -> {
                context.logger.error("Unable to connect to $workspaceName from $deploymentURL")
                context.showErrorPopup(MissingArgumentException("Can't handle URI because because we're unable to connect to workspace $workspaceName"))
                return
            }

            WorkspaceStatus.RUNNING -> Unit // All is well
        }

        // TODO: Show a dropdown and ask for an agent if missing.
        val agent: WorkspaceAgent
        try {
            agent = getMatchingAgent(params, workspace)
        } catch (e: IllegalArgumentException) {
            context.logger.error(e, "Can't resolve an agent for workspace $workspaceName from $deploymentURL")
            context.showErrorPopup(
                MissingArgumentException(
                    "Can't handle URI because we can't resolve an agent for workspace $workspaceName from $deploymentURL",
                    e
                )
            )
            return
        }
        val status = WorkspaceAndAgentStatus.from(workspace, agent)

        if (!status.ready()) {
            context.logger.error("Agent ${agent.name} for workspace $workspaceName from $deploymentURL is not ready")
            context.showErrorPopup(MissingArgumentException("Can't handle URI because agent ${agent.name} for workspace $workspaceName from $deploymentURL is not ready"))
            return
        }

        val cli = ensureCLI(
            context,
            deploymentURL.toURL(),
            restClient.buildInfo().version
        )

        // We only need to log in if we are using token-based auth.
        if (restClient.token != null) {
            context.logger.info("Authenticating Coder CLI...")
            cli.login(restClient.token)
        }

        context.logger.info("Configuring Coder CLI...")
        cli.configSsh(restClient.agentNames(workspaces))

        if (shouldWaitForAutoLogin) {
            isInitialized.waitForTrue()
        }
        reInitialize(restClient, cli)

        val environmentId = "${workspace.name}.${agent.name}"
        context.popupPluginMainPage()
        context.envPageManager.showEnvironmentPage(environmentId, false)
        val productCode = params.ideProductCode()
        val buildNumber = params.ideBuildNumber()
        val projectFolder = params.projectFolder()
        if (!productCode.isNullOrBlank() && !buildNumber.isNullOrBlank()) {
            context.cs.launch {
                val ideVersion = "$productCode-$buildNumber"
                context.logger.info("installing $ideVersion on $environmentId")
                val job = context.cs.launch {
                    context.ideOrchestrator.prepareClient(environmentId, ideVersion)
                }
                job.join()
                context.logger.info("launching $ideVersion on $environmentId")
                context.ideOrchestrator.connectToIde(environmentId, ideVersion, projectFolder)
            }
        }
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

    private suspend fun askUrl(): String? {
        context.popupPluginMainPage()
        return dialogUi.ask(
            context.i18n.ptrl("Deployment URL"),
            context.i18n.ptrl("Enter the full URL of your Coder deployment")
        )
    }

    /**
     * Return an authenticated Coder CLI, asking for the token.
     * Throw MissingArgumentException if the user aborts. Any network or invalid
     * token error may also be thrown.
     */
    private suspend fun authenticate(
        deploymentURL: String,
        tryToken: String?
    ): CoderRestClient {
        val token =
            if (settings.requireTokenAuth) {
                // Try the provided token immediately on the first attempt.
                if (!tryToken.isNullOrBlank()) {
                    tryToken
                } else {
                    context.popupPluginMainPage()
                    // Otherwise ask for a new token, showing the previous token.
                    dialogUi.askToken(deploymentURL.toURL())
                }
            } else {
                null
            }

        if (settings.requireTokenAuth && token == null) { // User aborted.
            throw MissingArgumentException("Token is required")
        }
        val client = CoderRestClient(
            context,
            deploymentURL.toURL(),
            token,
            PluginManager.pluginInfo.version
        )
        client.authenticate()
        return client
    }

}

/**
 * Follow a URL's redirects to its final destination.
 */
internal fun resolveRedirects(url: URL): URL {
    var location = url
    val maxRedirects = 10
    for (i in 1..maxRedirects) {
        val conn = location.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connect()
        val code = conn.responseCode
        val nextLocation = conn.getHeaderField("Location")
        conn.disconnect()
        // Redirects are triggered by any code starting with 3 plus a
        // location header.
        if (code < 300 || code >= 400 || nextLocation.isNullOrBlank()) {
            return location
        }
        // Location headers might be relative.
        location = URL(location, nextLocation)
    }
    throw Exception("Too many redirects")
}

/**
 * Return the agent matching the provided agent ID or name in the parameters.
 *
 * @throws [IllegalArgumentException]
 */
internal fun getMatchingAgent(
    parameters: Map<String, String?>,
    workspace: Workspace,
): WorkspaceAgent {
    val agents = workspace.latestBuild.resources.filter { it.agents != null }.flatMap { it.agents!! }
    if (agents.isEmpty()) {
        throw IllegalArgumentException("The workspace \"${workspace.name}\" has no agents")
    }

    // If the agent is missing and the workspace has only one, use that.
    // Prefer the ID over the name if both are set.
    val agent =
        if (!parameters.agentID().isNullOrBlank()) {
            agents.firstOrNull { it.id.toString() == parameters.agentID() }
        } else if (agents.size == 1) {
            agents.first()
        } else {
            null
        }

    if (agent == null) {
        if (!parameters.agentID().isNullOrBlank()) {
            throw IllegalArgumentException("The workspace \"${workspace.name}\" does not have an agent with ID \"${parameters.agentID()}\"")
        } else {
            throw MissingArgumentException(
                "Unable to determine which agent to connect to; \"$AGENT_ID\" must be set because the workspace \"${workspace.name}\" has more than one agent",
            )
        }
    }

    return agent
}

private suspend fun CoderToolboxContext.showErrorPopup(error: Throwable) {
    popupPluginMainPage()
    this.ui.showErrorInfoPopup(error)
}

private suspend fun CoderToolboxContext.showInfoPopup(
    title: LocalizableString,
    message: LocalizableString,
    okLabel: LocalizableString
) {
    popupPluginMainPage()
    this.ui.showInfoPopup(title, message, okLabel)
}

private fun CoderToolboxContext.popupPluginMainPage() {
    this.ui.showWindow()
    this.envPageManager.showPluginEnvironmentsPage(true)
}

/**
 * Suspends the coroutine until first true value is received.
 */
suspend fun StateFlow<Boolean>.waitForTrue() = this.first { it }

class MissingArgumentException(message: String, ex: Throwable? = null) : IllegalArgumentException(message, ex)
