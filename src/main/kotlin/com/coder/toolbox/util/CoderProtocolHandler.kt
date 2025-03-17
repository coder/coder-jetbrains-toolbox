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
import com.coder.toolbox.settings.CoderSettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

open class CoderProtocolHandler(
    private val context: CoderToolboxContext,
    private val settings: CoderSettings,
    private val httpClient: OkHttpClient?,
    private val dialogUi: DialogUi,
    private val isInitialized: StateFlow<Boolean>,
) {
    /**
     * Given a set of URL parameters, prepare the CLI then return a workspace to
     * connect.
     *
     * Throw if required arguments are not supplied or the workspace is not in a
     * connectable state.
     */
    suspend fun handle(
        uri: URI,
        onReinitialize: suspend (CoderRestClient, CoderCLIManager) -> Unit
    ) {
        val params = uri.toQueryParameters()

        val deploymentURL = params.url() ?: askUrl()
        if (deploymentURL.isNullOrBlank()) {
            context.logger.error("Query parameter \"$URL\" is missing from URI $uri")
            context.ui.showErrorInfoPopup(MissingArgumentException("Can't handle URI because query parameter \"$URL\" is missing"))
            return
        }

        val queryToken = params.token()
        val restClient = try {
            authenticate(deploymentURL, queryToken)
        } catch (ex: Exception) {
            context.logger.error(ex, "Query parameter \"$TOKEN\" is missing from URI $uri")
            context.ui.showErrorInfoPopup(
                IllegalStateException(
                    humanizeConnectionError(
                        deploymentURL.toURL(),
                        true,
                        ex
                    )
                )
            )
            return
        }

        // TODO: Show a dropdown and ask for the workspace if missing. Right now it's not possible because dialogs are quite limited
        val workspaceName = params.workspace()
        if (workspaceName.isNullOrBlank()) {
            context.logger.error("Query parameter \"$WORKSPACE\" is missing from URI $uri")
            context.ui.showErrorInfoPopup(MissingArgumentException("Can't handle URI because query parameter \"$WORKSPACE\" is missing"))
            return
        }

        val workspaces = restClient.workspaces()
        val workspace = workspaces.firstOrNull { it.name == workspaceName }
        if (workspace == null) {
            context.logger.error("There is no workspace with name $workspaceName on $deploymentURL")
            context.ui.showErrorInfoPopup(MissingArgumentException("Can't handle URI because workspace with name $workspaceName does not exist"))
            return
        }

        when (workspace.latestBuild.status) {
            WorkspaceStatus.PENDING, WorkspaceStatus.STARTING ->
                // TODO: Wait for the workspace to turn on.
                throw IllegalArgumentException(
                    "The workspace \"$workspaceName\" is ${
                        workspace.latestBuild.status.toString().lowercase()
                    }; please wait then try again",
                )

            WorkspaceStatus.STOPPING, WorkspaceStatus.STOPPED,
            WorkspaceStatus.CANCELING, WorkspaceStatus.CANCELED,
                ->
                // TODO: Turn on the workspace.
                throw IllegalArgumentException(
                    "The workspace \"$workspaceName\" is ${
                        workspace.latestBuild.status.toString().lowercase()
                    }; please start the workspace and try again",
                )

            WorkspaceStatus.FAILED, WorkspaceStatus.DELETING, WorkspaceStatus.DELETED ->
                throw IllegalArgumentException(
                    "The workspace \"$workspaceName\" is ${
                        workspace.latestBuild.status.toString().lowercase()
                    }; unable to connect",
                )

            WorkspaceStatus.RUNNING -> Unit // All is well
        }

        // TODO: Show a dropdown and ask for an agent if missing.
        val agent = getMatchingAgent(params, workspace)
        val status = WorkspaceAndAgentStatus.from(workspace, agent)

        if (status.pending()) {
            // TODO: Wait for the agent to be ready.
            throw IllegalArgumentException(
                "The agent \"${agent.name}\" has a status of \"${
                    status.toString().lowercase()
                }\"; please wait then try again",
            )
        } else if (!status.ready()) {
            throw IllegalArgumentException(
                "The agent \"${agent.name}\" has a status of \"${
                    status.toString().lowercase()
                }\"; unable to connect"
            )
        }

        val cli =
            ensureCLI(
                context,
                deploymentURL.toURL(),
                restClient.buildInfo().version,
                settings
            )

        // We only need to log in if we are using token-based auth.
        if (restClient.token != null) {
            context.logger.info("Authenticating Coder CLI...")
            cli.login(restClient.token)
        }

        context.logger.info("Configuring Coder CLI...")
        cli.configSsh(restClient.agentNames(workspaces))

        onReinitialize(restClient, cli)
        context.cs.launch {
            context.ui.showWindow()
            context.envPageManager.showPluginEnvironmentsPage(true)
            isInitialized.waitForTrue()
            context.envPageManager.showEnvironmentPage("${workspace.name}.${agent.name}", false)
            // without a yield or a delay(0) the env page does not show up. My assumption is that
            // the coroutine is finishing too fast without giving enough time to compose main thread
            // to catch the state change. Yielding gives other coroutines the chance to run
            yield()
        }
    }

    private suspend fun askUrl(): String? {
        context.ui.showWindow()
        context.envPageManager.showPluginEnvironmentsPage(false)
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
                    context.ui.showWindow()
                    context.envPageManager.showPluginEnvironmentsPage(false)
                    // Otherwise ask for a new token, showing the previous token.
                    dialogUi.askToken(deploymentURL.toURL())
                }
            } else {
                null
            }

        if (settings.requireTokenAuth && token == null) { // User aborted.
            throw MissingArgumentException("Token is required")
        }
        // The http client Toolbox gives us is already set up with the
        // proxy config, so we do net need to explicitly add it.
        val client = CoderRestClient(
            context,
            deploymentURL.toURL(),
            token,
            settings,
            proxyValues = null, // TODO - not sure the above comment applies as we are creating our own http client
            PluginManager.pluginInfo.version,
            httpClient
        )
        client.authenticate()
        return client
    }

    /**
     * Check that the link is allowlisted.  If not, confirm with the user.
     */
    private suspend fun verifyDownloadLink(parameters: Map<String, String>) {
        val link = parameters.ideDownloadLink()
        if (link.isNullOrBlank()) {
            return // Nothing to verify
        }

        val url =
            try {
                link.toURL()
            } catch (ex: Exception) {
                throw IllegalArgumentException("$link is not a valid URL")
            }

        val (allowlisted, https, linkWithRedirect) =
            try {
                isAllowlisted(url)
            } catch (e: Exception) {
                throw IllegalArgumentException("Unable to verify $url: $e")
            }
        if (allowlisted && https) {
            return
        }

        val comment =
            if (allowlisted) {
                "The download link is from a non-allowlisted URL"
            } else if (https) {
                "The download link is not using HTTPS"
            } else {
                "The download link is from a non-allowlisted URL and is not using HTTPS"
            }

        if (!dialogUi.confirm(
                context.i18n.ptrl("Confirm download URL"),
                context.i18n.pnotr("$comment. Would you like to proceed to $linkWithRedirect?"),
            )
        ) {
            throw IllegalArgumentException("$linkWithRedirect is not allowlisted")
        }
    }
}

/**
 * Return if the URL is allowlisted, https, and the URL and its final
 * destination, if it is a different host.
 */
private fun isAllowlisted(url: URL): Triple<Boolean, Boolean, String> {
    // TODO: Setting for the allowlist, and remember previously allowed
    //  domains.
    val domainAllowlist = listOf("intellij.net", "jetbrains.com")

    // Resolve any redirects.
    val finalUrl = resolveRedirects(url)

    var linkWithRedirect = url.toString()
    if (finalUrl.host != url.host) {
        linkWithRedirect = "$linkWithRedirect (redirects to to $finalUrl)"
    }

    val allowlisted =
        domainAllowlist.any { url.host == it || url.host.endsWith(".$it") } &&
                domainAllowlist.any { finalUrl.host == it || finalUrl.host.endsWith(".$it") }
    val https = url.protocol == "https" && finalUrl.protocol == "https"
    return Triple(allowlisted, https, linkWithRedirect)
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
 * The name is ignored if the ID is set.  If neither was supplied and the
 * workspace has only one agent, return that.  Otherwise throw an error.
 *
 * @throws [MissingArgumentException, IllegalArgumentException]
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
        } else if (!parameters.agentName().isNullOrBlank()) {
            agents.firstOrNull { it.name == parameters.agentName() }
        } else if (agents.size == 1) {
            agents.first()
        } else {
            null
        }

    if (agent == null) {
        if (!parameters.agentID().isNullOrBlank()) {
            throw IllegalArgumentException("The workspace \"${workspace.name}\" does not have an agent with ID \"${parameters.agentID()}\"")
        } else if (!parameters.agentName().isNullOrBlank()) {
            throw IllegalArgumentException(
                "The workspace \"${workspace.name}\"does not have an agent named \"${parameters.agentName()}\"",
            )
        } else {
            throw MissingArgumentException(
                "Unable to determine which agent to connect to; one of \"$AGENT_NAME\" or \"$AGENT_ID\" must be set because the workspace \"${workspace.name}\" has more than one agent",
            )
        }
    }

    return agent
}

fun StateFlow<Boolean>.waitForTrue() {
    this.filter { it }
}

class MissingArgumentException(message: String, ex: Throwable? = null) : IllegalArgumentException(message, ex)
