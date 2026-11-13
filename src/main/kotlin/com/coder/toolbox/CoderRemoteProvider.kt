package com.coder.toolbox

import com.coder.toolbox.browser.browse
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.feed.IdeFeedManager
import com.coder.toolbox.oauth.OAuth2Client
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.ex.OAuthTokenResponseException
import com.coder.toolbox.sdk.v2.models.InvalidCoderIdentifierException
import com.coder.toolbox.sdk.v2.models.User
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.session.SessionId
import com.coder.toolbox.util.CoderProtocolHandler
import com.coder.toolbox.util.DialogUi
import com.coder.toolbox.util.TOKEN
import com.coder.toolbox.util.URL
import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.hasSameOrigin
import com.coder.toolbox.util.origin
import com.coder.toolbox.util.owner
import com.coder.toolbox.util.toQueryParameters
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.token
import com.coder.toolbox.util.url
import com.coder.toolbox.util.validateStrictWebUrl
import com.coder.toolbox.util.withPath
import com.coder.toolbox.util.workspace
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.CoderDelimiter
import com.coder.toolbox.views.CoderSettingsPage
import com.coder.toolbox.views.CoderSetupWizardPage
import com.coder.toolbox.views.NewEnvironmentPage
import com.coder.toolbox.views.SuspendBiConsumer
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.ConnectionOrigin
import com.coder.toolbox.views.state.Credentials
import com.coder.toolbox.views.state.PageRouter
import com.coder.toolbox.views.state.PendingOAuthConnection
import com.coder.toolbox.views.state.toSessionContext
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon.IconType
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import com.jetbrains.toolbox.api.ui.components.AccountDropdownField as dropDownFactory

private val POLL_INTERVAL = 5.seconds
private const val CAN_T_HANDLE_URI_TITLE = "Can't handle URI"
private const val FAILED_TO_HANDLE_OAUTH2_TITLE = "Failed to handle OAuth2 request"
private const val SSH_CONFIGURATION_WARNING_TITLE = "SSH configuration could not be updated"

private fun List<CoderRemoteEnvironment>.currentSessionIds(): Set<SessionId> =
    mapNotNull(CoderRemoteEnvironment::currentSessionId).toSet()

@OptIn(ExperimentalCoroutinesApi::class)
class CoderRemoteProvider(
    private val context: CoderToolboxContext,
) : RemoteProvider("Coder") {
    // Current polling job.
    private var pollJob: Job? = null
    internal val lastEnvironments = mutableListOf<CoderRemoteEnvironment>()
    private var isSshConfigurationWarningShown = false

    private val sshConfigTrigger = Channel<String?>(Channel.CONFLATED)
    private val workspaceRefreshTrigger = Channel<Boolean>(Channel.CONFLATED)
    private val providerVisibleTrigger = Channel<Boolean>(Channel.CONFLATED)
    private val dialogUi = DialogUi(context)

    // The REST client, if we are signed in
    private var client: CoderRestClient? = null
    private var cli: CoderCLIManager? = null

    // On the first load, automatically log in if we can.
    private var firstRun = true

    private val isInitialized: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val coderHeaderPage = NewEnvironmentPage(
        context,
        context.i18n.pnotr(context.deploymentUrl.toString()),
        workspaceRefreshTrigger,
        templatesProvider = { client?.templates() ?: emptyList() }
    )
    private val settingsPage: CoderSettingsPage = CoderSettingsPage(context, sshConfigTrigger) {
        client?.let { restClient ->
            if (context.settingsStore.useAppNameAsTitle) {
                coderHeaderPage.setTitle(context.i18n.pnotr(restClient.appName))
            } else {
                coderHeaderPage.setTitle(context.i18n.pnotr(restClient.url.toString()))
            }
        }
    }
    override val loadingEnvironmentsDescription: LocalizableString = context.i18n.ptrl("Loading workspaces...")
    override val environments: MutableStateFlow<LoadableState<List<CoderRemoteEnvironment>>> = MutableStateFlow(
        LoadableState.Loading
    )
    private val linkHandler =
        CoderProtocolHandler(context, IdeFeedManager(context), workspaceRefreshTrigger, environments)
    private val accountDropdownField = dropDownFactory(context.i18n.pnotr("")) {
        context.cs.launch(CoroutineName("Logout")) {
            logout()
            context.envPageManager.showPluginEnvironmentsPage(false)
        }
    }.apply {
        visibility.update { false }
    }

    private val router = PageRouter()

    /**
     * With the provided client, start polling for workspaces.  Every time a new
     * workspace is added, reconfigure SSH using the provided cli (including the
     * first time).
     */
    internal fun poll(client: CoderRestClient, cli: CoderCLIManager): Job =
        context.cs.launch(CoroutineName("Workspace Poller")) {
            var lastPollTime = TimeSource.Monotonic.markNow()
            while (isActive) {
                var sessionIds = lastEnvironments.currentSessionIds()
                try {
                    context.logger.debug(sessionIds, "Fetching workspace agents from ${client.url}")
                    val resolvedEnvironments = resolveWorkspaceEnvironments(client, cli)

                    // In case we logged out while running the query.
                    if (!isActive) {
                        return@launch
                    }

                    // Sessions can start while the workspace request is in flight. Refresh the
                    // snapshot before disposal so it includes those sessions and retains the IDs
                    // of environments removed by this result.
                    sessionIds = lastEnvironments.currentSessionIds()

                    val removedEnvironments = lastEnvironments.filter { it !in resolvedEnvironments }

                    // Reconfigure if environments changed.
                    if (lastEnvironments.size != resolvedEnvironments.size || lastEnvironments != resolvedEnvironments) {
                        context.logger.info(
                            sessionIds,
                            "Workspaces have changed, reconfiguring CLI: $resolvedEnvironments",
                        )
                        configureSsh(cli, resolvedEnvironments)
                    }

                    // Toolbox closes removed environments without firing their disconnect hooks.
                    // Dispose them after SSH configuration has captured their session IDs.
                    removedEnvironments.forEach { it.dispose() }

                    environments.update {
                        LoadableState.Value(resolvedEnvironments)
                    }
                    if (!isInitialized.value) {
                        context.logger.info("Environments for ${client.url} are now initialized")
                        isInitialized.update {
                            true
                        }
                    }
                    lastEnvironments.apply {
                        clear()
                        addAll(resolvedEnvironments)
                    }
                } catch (_: CancellationException) {
                    context.logger.debug(sessionIds, "${client.url} polling loop canceled")
                    break
                } catch (ex: Exception) {
                    val elapsed = lastPollTime.elapsedNow()
                    if (elapsed > POLL_INTERVAL * 2) {
                        context.logger.info(sessionIds, "wake-up from an OS sleep was detected")
                    } else {
                        if ((ex is APIResponseException && ex.isTokenExpired) || ex is OAuthTokenResponseException) {
                            close()
                            context.envPageManager.showPluginEnvironmentsPage(false)
                            context.logger.logAndShowError(
                                sessionIds,
                                "Error encountered while setting up Coder",
                                "Your Coder session has expired. Please re-authenticate and try again.",
                                ex
                            )
                            context.logger.flush("coder_session_expired")
                            break
                        }
                        context.logger.error(sessionIds, ex, "workspace polling error encountered")
                    }
                }

                select {
                    onTimeout(POLL_INTERVAL) {
                        context.logger.debug(
                            lastEnvironments.currentSessionIds(),
                            "workspace poller waked up by the $POLL_INTERVAL timeout",
                        )
                    }
                    sshConfigTrigger.onReceive { staleSshConfigPath ->
                        val currentSessionIds = lastEnvironments.currentSessionIds()
                        context.logger.debug(
                            currentSessionIds,
                            "workspace poller waked up because it should reconfigure the ssh configurations",
                        )
                        configureSsh(
                            cli,
                            lastEnvironments,
                            staleSshConfigPath,
                        )
                    }
                    workspaceRefreshTrigger.onReceive { shouldTrigger ->
                        if (shouldTrigger) {
                            context.logger.debug(
                                lastEnvironments.currentSessionIds(),
                                "workspace poller waked up to fetch workspaces from the latest header settings",
                            )
                        }
                    }
                    providerVisibleTrigger.onReceive { isCoderProviderVisible ->
                        if (isCoderProviderVisible) {
                            context.logger.debug(
                                lastEnvironments.currentSessionIds(),
                                "workspace poller waked up by Coder Toolbox which is currently visible, fetching latest workspace statuses",
                            )
                        }
                    }
                }
                lastPollTime = TimeSource.Monotonic.markNow()
            }
        }

    /**
     * Keep SSH configuration failures separate from workspace discovery.  SSH
     * configuration is necessary to connect, but a read-only or malformed SSH
     * config must not prevent Toolbox from showing the workspaces it resolved.
     *
     * The affected sessions are captured from [lastEnvironments] before removed environments are
     * disposed. They cannot be reconstructed from [resolvedEnvironments], which no longer contains
     * those environments.
     */
    private fun configureSsh(
        cli: CoderCLIManager,
        resolvedEnvironments: List<CoderRemoteEnvironment>,
        staleSshConfigPath: String? = null,
    ) {
        val sessionIds = lastEnvironments.currentSessionIds()
        try {
            cli.configSsh(
                resolvedEnvironments.mapNotNull { it.toWorkspaceAddressOrNull() }.toSet(),
                sessionIds = sessionIds,
                sshConfigPath = context.settingsStore.sshConfigPath,
            )
            isSshConfigurationWarningShown = false

            // Only attempt cleanup if the previous file actually exists; configSsh would
            // otherwise create a stray file there just to hold an empty managed block.
            if (staleSshConfigPath != null && Path.of(staleSshConfigPath).toFile().exists()) {
                runCatching {
                    cli.configSsh(
                        emptySet(),
                        sessionIds = sessionIds,
                        sshConfigPath = staleSshConfigPath,
                    )
                }.onFailure { ex ->
                    context.logger.warn(
                        sessionIds,
                        ex,
                        "Failed to remove the managed SSH config block from the previous location: $staleSshConfigPath"
                    )
                }
            }
        } catch (ex: Exception) {
            // Identifier failures are security boundary violations, not recoverable file-system errors.
            // Let the outer poll handler reject the response before it publishes the environments.
            if (ex.hasInvalidCoderIdentifierCause()) throw ex

            if (!isSshConfigurationWarningShown) {
                isSshConfigurationWarningShown = true
                val reason = ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName
                context.logger.logAndShowWarning(
                    sessionIds,
                    SSH_CONFIGURATION_WARNING_TITLE,
                    "Workspaces remain available, but SSH connections are unavailable: $reason. " +
                            "Update ${context.settingsStore.sshConfigPath} and try again.",
                    ex,
                )
            } else {
                context.logger.warn(
                    sessionIds,
                    ex,
                    "Failed to update SSH configuration at ${context.settingsStore.sshConfigPath}"
                )
            }
        }
    }

    /**
     * Resolves workspace agents into remote environments.
     *
     * For each workspace:
     * - If running, uses agents from the latest build resources.
     * - If not running, creates a workspace-only environment without resolving agents.
     *
     * @return a sorted list of resolved remote environments
     */
    internal suspend fun resolveWorkspaceEnvironments(
        client: CoderRestClient,
        cli: CoderCLIManager,
    ): List<CoderRemoteEnvironment> {
        val workspaces = try {
            client.workspaces(coderHeaderPage.workspaceSearchQuery.value)
        } catch (ex: APIResponseException) {
            // Surface invalid search queries on the header instead of failing the whole poll.
            if (ex.isValidationError) {
                coderHeaderPage.reportFilterError(ex.validationMessage)
                return emptyList()
            }
            throw ex
        }
        coderHeaderPage.resetError()
        return workspaces.flatMap { ws ->
            if (ws.latestBuild.status != WorkspaceStatus.RUNNING) {
                return@flatMap listOf(
                    lastEnvironments.firstOrNull { it.id == ws.name }
                        ?.also { it.update(ws, null) }
                        ?: CoderRemoteEnvironment(context, client, cli, workspaceRefreshTrigger, ws, null)
                )
            }

            ws.latestBuild.resources
                .flatMap { it.agents ?: emptyList() }
                .distinctBy { it.name }
                .map { agent ->
                    lastEnvironments.firstOrNull { it.id == "${ws.name}.${agent.name}" }
                        ?.also {
                            // If we have an environment already, update that.
                            it.update(ws, agent)
                        } ?: CoderRemoteEnvironment(context, client, cli, workspaceRefreshTrigger, ws, agent)
                }

        }.sortedBy { it.id }
    }

    /** Sign out explicitly; provider shutdown alone must preserve credentials. */
    internal suspend fun logout() {
        val sessionIds = lastEnvironments.currentSessionIds()
        val activeClient = client
        val activeCli = cli
        context.logger.info(sessionIds, "Logging out ${activeClient?.me?.username}...")
        context.logger.flush("logout")
        try {
            pollJob?.cancelAndJoin()
            activeClient?.close()
            if (activeClient != null) {
                context.secrets.clearSessionFor(activeClient.url)
                if (activeCli?.usesTokenAuth == true) activeCli.logout()
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            context.logger.logAndShowWarning(
                sessionIds,
                "CLI logout failed",
                "Toolbox has signed out, but the CLI credential may remain stored. " +
                        "Sign in and try logging out again to remove it.",
                ex,
            )
            context.logger.flush("cli_logout_failed")
        } finally {
            close()
        }
    }

    /**
     * A dropdown that appears at the top of the environment list to the right.
     */
    override fun getAccountDropDown() = accountDropdownField

    override val additionalPluginActions: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            Action(context, "Create workspace") {
                val url = context.settingsStore.workspaceCreateUrl ?: client?.url?.withPath("/templates").toString()
                context.desktop.browse(
                    url
                        .replace("\$workspaceOwner", client?.me?.username ?: "")
                ) {
                    context.ui.showErrorInfoPopup(it)
                }
            },
            CoderDelimiter(context.i18n.pnotr("")),
            Action(context, "Settings") {
                context.ui.showUiPage(settingsPage)
            },
        )
    )

    /**
     * Cancel polling and clear the client and environments.
     *
     * Also called as part of our own logout.
     */
    override fun close() {
        resetConnection()
        router.clear()
    }

    private fun resetConnection() {
        val sessionIds = lastEnvironments.currentSessionIds()
        softClose()
        client = null
        cli = null
        lastEnvironments.forEach { it.dispose() }
        lastEnvironments.clear()
        isSshConfigurationWarningShown = false
        environments.value = LoadableState.Value(emptyList())
        isInitialized.update { false }
        accountDropdownField.visibility.update { false }
        context.logger.info(sessionIds, "Coder plugin is now closed")
        context.logger.flush("provider_closed")
    }

    private fun softClose() {
        val sessionIds = lastEnvironments.currentSessionIds()
        pollJob?.let {
            it.cancel()
            context.logger.info(sessionIds, "Cancelled workspace poll job ${pollJob.toString()}")
        }
        pollJob = null
        client?.let {
            it.close()
            context.logger.info(sessionIds, "REST API client closed and resources released")
        }
    }

    override val svgIcon: SvgIcon =
        SvgIcon(
            this::class.java.getResourceAsStream("/icon.svg")?.readAllBytes() ?: byteArrayOf(),
            type = IconType.Masked
        )

    override val noEnvironmentsSvgIcon: SvgIcon? =
        SvgIcon(
            this::class.java.getResourceAsStream("/icon.svg")?.readAllBytes() ?: byteArrayOf(),
            type = IconType.Masked
        )

    /**
     * TODO@JB: It would be nice to show "loading workspaces" at first but it
     *          appears to be only called once.
     */
    override val noEnvironmentsDescription: String? = "No workspaces yet"

    /**
     * Toolbox 3.5 removes the entire top section when this is false. Coder uses
     * the new-environment page as a provider header so the deployment URL and
     * account dropdown remain visible above the workspace list.
     */
    override val canCreateNewEnvironments: Boolean = true

    /**
     * Just displays the deployment URL at the moment, but we could use this as
     * a form for creating new environments.
     */
    override fun getNewEnvironmentUiPage(): UiPage = coderHeaderPage

    /**
     * We always show a list of environments.
     */
    override val isSingleEnvironment: Boolean = false

    /**
     *  TODO: Possibly a good idea to start/stop polling based on visibility, at
     *        the cost of momentarily stale data.  It would not be bad if we had
     *        a place to put a timer ("last updated 10 seconds ago" for example)
     *        and a manual refresh button.
     */
    override fun setVisible(visibility: ProviderVisibilityState) {
        if (visibility.providerVisible) {
            context.cs.launch(CoroutineName("Notify Plugin Visibility")) {
                providerVisibleTrigger.send(true)
            }
        }
    }

    /**
     * Handle incoming links (like from the dashboard).
     */
    override suspend fun handleUri(uri: URI) {
        try {
            require(uri.scheme.equals("jetbrains", ignoreCase = true) &&
                    uri.rawAuthority.equals("gateway", ignoreCase = true) && uri.rawFragment == null &&
                    uri.rawPath in setOf("/com.coder.toolbox", "/coder", "/com.coder.toolbox/auth")) {
                "Unrecognized Toolbox link."
            }
            val params = uri.toQueryParameters()
            when {
                uri.rawPath == "/com.coder.toolbox/auth" -> handleOAuthUri(params)
                // An empty query can indicate plugin installation.
                params.isEmpty() ->
                    context.logger.logAndShowInfo("URI will not be handled", "No query parameters were provided")

                else -> handleWorkspaceUri(params)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val reason = (ex as? APIResponseException)?.reason?.takeIf { it.isNotBlank() }
            context.logger.logAndShowError("Error encountered while handling Coder URI", reason ?: ex.message.orEmpty())
        }
    }

    private suspend fun handleOAuthUri(params: Map<String, String>) {
        // RFC 6749 §4.1.2.1 (also covers RFC 7636 §4.4.1 PKCE errors): the authorization
        // server redirects back with `error` when authorization fails (e.g. access_denied,
        // invalid_request, unsupported_response_type, server_error, ...).
        val error = params["error"]
        if (error != null) {
            val description = params["error_description"]?.let { " - $it" } ?: ""
            return context.logger.logAndShowError(
                FAILED_TO_HANDLE_OAUTH2_TITLE,
                "OAuth2 authorization error: $error$description"
            )
        }

        if (!router.hasActiveWizard) {
            return context.logger.logAndShowError(
                FAILED_TO_HANDLE_OAUTH2_TITLE,
                "OAuth2 callback arrived but the setup wizard is no longer active"
            )
        }
        val pendingOAuthConnection = router.pendingOAuthConnection ?: return context.logger.logAndShowError(
            FAILED_TO_HANDLE_OAUTH2_TITLE,
            "OAuth2 callback arrived but no OAuth session was started"
        )
        params["state"]?.takeIf { it == pendingOAuthConnection.session.state }
            ?: return context.logger.logAndShowError(
                FAILED_TO_HANDLE_OAUTH2_TITLE,
                "Server responded back with an invalid state that does not match the initial authorization state sent to the server"
            )

        val code = params["code"] ?: return context.logger.logAndShowError(
            FAILED_TO_HANDLE_OAUTH2_TITLE,
            "OAuth2 server did not respond back with an access token"
        )
        // before going forward we check to make sure OAuth is not disabled in the meantime
        if (!context.settingsStore.preferOAuth2IfAvailable) {
            context.logger.logAndShowError(
                FAILED_TO_HANDLE_OAUTH2_TITLE,
                "OAuth based authentication is not enabled for Coder plugin in Toolbox. Please enable it in plugin settings or use the API token instead."
            )
            return
        }
        exchangeOAuthCodeForToken(code, pendingOAuthConnection)
    }

    private suspend fun exchangeOAuthCodeForToken(
        code: String,
        pendingOAuthConnection: PendingOAuthConnection,
    ) {
        try {
            context.logger.info("Handling OAuth callback...")

            val oauthSessionContext = pendingOAuthConnection.session
            val tokenResponse = OAuth2Client(context).exchangeCode(oauthSessionContext, code)
            val wizard = CoderSetupWizardPage.connectStep(
                context, settingsPage,
                url = pendingOAuthConnection.url,
                credentials = Credentials.OAuth(oauthSessionContext.copy(tokenResponse = tokenResponse)),
                onConnect = onConnect,
                onTokenRefreshed = ::onTokenRefreshed,
            )
            firstRun = false
            router.navigate(wizard)

            context.envPageManager.showPluginEnvironmentsPage(false)
            context.ui.showUiPage(wizard)
        } catch (e: Exception) {
            context.logger.logAndShowError("OAuth Error", "Exception during token exchange: ${e.message}", e)
        }
    }

    internal suspend fun onTokenRefreshed(url: URL, oauthSessionCtx: CoderOAuthSessionContext) {
        // A refresh may finish after the user has connected to another deployment.
        val activeCli = cli?.takeIf {
            client?.url?.hasSameOrigin(url) == true && it.deploymentURL.hasSameOrigin(url)
        }
        oauthSessionCtx.tokenResponse?.accessToken?.let { activeCli?.login(it) }
        context.secrets.storeOAuthFor(url.toString(), oauthSessionCtx)
    }

    private suspend fun handleWorkspaceUri(params: Map<String, String>) {
        val deployment = resolveDeploymentParameters(params) ?: return
        context.logger.info("Handling workspace link for ${deployment.url}")
        val activeClient = client?.takeIf { it.url.hasSameOrigin(deployment.url) }
        if (!confirmDeploymentSignIn(deployment, activeClient)) return

        firstRun = false
        if (activeClient != null) {
            openLinkInActiveSession(params, activeClient, deployment.token)
        } else {
            connectFromLink(params, deployment.url, deployment.token)
        }
    }

    private suspend fun resolveDeploymentParameters(params: Map<String, String>): ResolvedDeploymentParameters? {
        val url = resolveDeploymentUrl(params) ?: return null
        val token = if (context.settingsStore.requiresMTlsAuth) null else resolveToken(params) ?: return null
        return ResolvedDeploymentParameters(url, token)
    }

    private suspend fun resolveDeploymentUrl(params: Map<String, String>): URL? {
        val deploymentURL = params.url() ?: askUrl()
        if (deploymentURL.isNullOrBlank()) {
            context.logger.logAndShowError(CAN_T_HANDLE_URI_TITLE, "Query parameter \"${URL}\" is missing from URI")
            return null
        }
        val validationResult = deploymentURL.validateStrictWebUrl(originOnly = true)
        if (validationResult is Invalid) {
            context.logger.logAndShowError(CAN_T_HANDLE_URI_TITLE, "\"$URL\" is invalid: ${validationResult.reason}")
            return null
        }
        // Workspace links select a deployment by scheme, host, and port.
        val origin = requireNotNull(deploymentURL.toHttpUrlOrNull()?.origin()) {
            "The deployment URL must have a valid HTTP(S) host and port."
        }
        val saved = context.secrets.savedDeploymentUrl(origin.toURL())
        if (!saved.isNullOrEmpty() && saved.validateStrictWebUrl() !is Invalid && saved.toHttpUrlOrNull()?.origin() == origin) {
            return saved.toURL()
        }
        return origin.toURL()
    }

    private suspend fun askUrl(): String? {
        context.popupPluginMainPage()
        return dialogUi.ask(
            context.i18n.ptrl("Deployment URL"),
            context.i18n.ptrl("Enter the full URL of your Coder deployment")
        )
    }

    private fun resolveToken(params: Map<String, String>): String? {
        val token = params.token()
        if (token.isNullOrBlank()) {
            context.logger.logAndShowError(CAN_T_HANDLE_URI_TITLE, "Query parameter \"$TOKEN\" is missing from URI")
            return null
        }
        return token
    }

    private suspend fun confirmDeploymentSignIn(
        deployment: ResolvedDeploymentParameters,
        activeClient: CoderRestClient?,
    ): Boolean {
        if (activeClient == null && !context.secrets.isUrlTrusted(deployment.url)) {
            return confirmDeployment(deployment.url.toString(), deployment.token != null)
        }
        val token = deployment.token ?: return true
        return confirmTokenSignIn(activeClient?.url ?: deployment.url, token, activeClient?.me)
    }

    private suspend fun confirmDeployment(origin: String, hasToken: Boolean): Boolean {
        val disclosure = if (hasToken) {
            " This link contains a token that will be sent to this deployment to sign you in."
        } else ""
        context.ui.showWindowSuspending()
        return context.ui.showYesNoPopup(
            context.i18n.ptrl("Connect to a Coder deployment?"),
            context.i18n.pnotr(
                "$origin\n\nYou have not signed in to this deployment in Toolbox before.$disclosure " +
                        "Continue only if you trust it. Connecting can update your SSH configuration."
            ),
            context.i18n.ptrl("Trust and Continue"),
            context.i18n.ptrl("Cancel"),
        )
    }

    private suspend fun confirmTokenSignIn(url: URL, token: String, activeUser: User?): Boolean {
        val linkUser = requireNotNull(userForToken(url, token)) { "The token in the link is invalid or expired." }
        val previousUser = activeUser ?: (autoSetupCredentials(url) as? Credentials.Token)?.let { saved ->
            if (saved.value == token) linkUser else userForToken(url, saved.value)
        }
        if (linkUser.id == previousUser?.id) return true
        val previousSession = previousUser?.let { "your saved session for \"${it.username}\"" } ?: "your saved session"
        context.ui.showWindowSuspending()
        return context.ui.showYesNoPopup(
            context.i18n.ptrl("Sign in with the token from the link?"),
            context.i18n.pnotr(
                "${url.origin()}\n\nThis link signs you in as \"${linkUser.username}\", " +
                        "replacing $previousSession. Continue only if you intended to use this account."
            ),
            context.i18n.ptrl("Sign In"),
            context.i18n.ptrl("Cancel"),
        )
    }

    private suspend fun userForToken(url: URL, token: String): User? {
        val candidate = CoderRestClient(context, url, token, pluginVersion = PluginManager.pluginInfo.version)
        return try {
            candidate.me()
        } catch (ex: APIResponseException) {
            if (!ex.isUnauthorized) throw ex
            null
        } finally {
            candidate.close()
        }
    }

    private fun autoSetupCredentials(url: URL): Credentials? {
        if (url.toString().validateStrictWebUrl() is Invalid) return null
        if (context.settingsStore.requiresMTlsAuth) return Credentials.MTls

        val tokenCredentials = context.secrets.apiTokenFor(url)
            ?.takeIf { it.isNotBlank() }
            ?.let { Credentials.Token(it) }

        if (!context.settingsStore.preferOAuth2IfAvailable) return tokenCredentials

        return context.secrets.oauthSessionFor(url.toString())?.let {
            Credentials.OAuth(it.toSessionContext())
        } ?: tokenCredentials
    }

    private suspend fun openLinkInActiveSession(
        params: Map<String, String>,
        activeClient: CoderRestClient,
        token: String?,
    ) {
        coderHeaderPage.isBusy.update { true }
        try {
            val (sessionClient, sessionCli) = if (context.settingsStore.requiresTokenAuth && token != null) {
                refreshSession(activeClient.url, token)
            } else {
                activeClient to checkNotNull(cli) { "The active Coder connection has no CLI." }
            }
            handleLink(params, activeClient.url, sessionClient, sessionCli)
        } finally {
            coderHeaderPage.isBusy.update { false }
        }
    }

    private suspend fun refreshSession(url: URL, token: String): Pair<CoderRestClient, CoderCLIManager> {
        context.logger.info(
            lastEnvironments.currentSessionIds(),
            "Preparing the HTTP client and CLI with the token from the workspace link",
        )
        val newRestClient = CoderRestClient(
            context,
            url,
            token,
            null,
            PluginManager.pluginInfo.version,
        )
        val newCli = try {
            newRestClient.initializeSession()
            CoderCLIManager(context, url).apply {
                login(token)
                storeSession(newRestClient)
            }
        } catch (ex: Exception) {
            newRestClient.close()
            throw ex
        }
        softClose()
        this.client = newRestClient
        this.cli = newCli
        lastEnvironments.forEach { it.updateClientAndCli(newRestClient, newCli) }
        accountDropdownField.labelState.update { context.i18n.pnotr(newRestClient.me.username) }
        accountDropdownField.visibility.update { true }
        coderHeaderPage.resetFilter()
        context.cs.launch(CoroutineName("Load Templates")) { coderHeaderPage.reloadTemplates() }
        pollJob = poll(newRestClient, newCli)
        context.logger.info(
            lastEnvironments.currentSessionIds(),
            "Workspace poll job with name ${pollJob.toString()} was created while handling URI",
        )
        return newRestClient to newCli
    }

    private fun storeSession(client: CoderRestClient) {
        context.settingsStore.updateLastUsedUrl(client.url)
        if (context.settingsStore.requiresTokenAuth) {
            client.token?.let { context.secrets.storeApiTokenFor(client.url, it) }
        }
        context.secrets.trustDeployment(client.url)
        context.logger.info("Deployment session was stored and will be available for automatic connection")
    }

    /**
     * Applies the appropriate workspace filter for the URI then delegates to [linkHandler].
     * Scopes the filter to the target workspace when it is owned by a different user so the poll
     * fetches and registers it before the IDE launch runs. For own workspaces, resets to the
     * default filter, clearing any leftover non-owned filter from a previous URI.
     */
    private suspend fun handleLink(
        params: Map<String, String>,
        url: URL,
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) {
        val uriOwner = params.owner()
        val uriWorkspace = params.workspace()
        if (!uriOwner.isNullOrBlank() && !uriWorkspace.isNullOrBlank() && uriOwner != client.me.username) {
            coderHeaderPage.setFilter("owner:$uriOwner name:$uriWorkspace")
        } else {
            coderHeaderPage.resetFilter()
        }
        linkHandler.handle(params, url, client, cli)
    }

    private suspend fun connectFromLink(params: Map<String, String>, url: URL, token: String?) {
        val credentials = token?.let { Credentials.Token(it) } ?: Credentials.MTls
        val wizard = CoderSetupWizardPage.connectStep(
            context, settingsPage,
            url = url,
            credentials = credentials,
            onConnect = onConnect.andThen(deferredLinkHandler(params, url)),
            onTokenRefreshed = ::onTokenRefreshed,
        )
        // Replace any in-flight wizard before showPluginEnvironmentsPage() requests a refresh.
        // If Toolbox calls getOverrideUiPage(), it must get the wizard for this link.
        router.navigate(wizard)
        context.popupPluginMainPage()
        // An already visible provider can keep showing the old wizard, so show the new one explicitly.
        context.ui.showUiPageSuspending(wizard)
    }

    /** Returns a [SuspendBiConsumer] that handles the given link parameters in a background coroutine. */
    private fun deferredLinkHandler(
        params: Map<String, String>,
        deploymentUrl: URL,
    ): SuspendBiConsumer<CoderRestClient, CoderCLIManager> = SuspendBiConsumer { client, cli ->
        context.cs.launch(CoroutineName("Deferred Link Handler")) {
            coderHeaderPage.isBusy.update { true }
            try {
                handleLink(params, deploymentUrl, client, cli)
            } catch (ex: Exception) {
                if (ex is kotlinx.coroutines.CancellationException) {
                    ensureActive()
                }
                context.logger.logAndShowError(
                    "Error handling deferred link",
                    ex.message ?: ""
                )
            } finally {
                coderHeaderPage.isBusy.update { false }
            }
        }
    }

    /** Show interactive setup when needed. Saved sessions restore behind the workspace list. */
    override fun getOverrideUiPage(): UiPage? {
        // Show the setup wizard if one is already scheduled.
        router.activePage?.let { return it.pageForDisplay() }

        // Let the default workspace UI render if the HTTP client is initialized.
        if (client != null) return null

        // Otherwise, schedule our own setup wizard.
        return router.getOrCreate { buildSetupWizard() }.pageForDisplay()
    }

    /**
     * Build the wizard for the current state. Called once per provider lifetime
     * (until [close] clears the router); subsequent visibility cycles reuse the
     * same instance, preserving any in-flight connect job.
     */
    private fun buildSetupWizard(): CoderSetupWizardPage {
        // When coming back to the application, initializeSession immediately.
        if (shouldDoAutoSetup()) {
            try {
                val url = context.deploymentUrl
                val credentials = autoSetupCredentials(url) ?: return CoderSetupWizardPage.deploymentUrlStep(
                    context, settingsPage,
                    onConnect = onConnect,
                    onTokenRefreshed = ::onTokenRefreshed,
                )
                return CoderSetupWizardPage.connectStep(
                    context, settingsPage,
                    url = url,
                    credentials = credentials,
                    onConnect = onConnect,
                    onTokenRefreshed = ::onTokenRefreshed,
                    origin = ConnectionOrigin.SAVED_SESSION,
                )
            } catch (ex: Exception) {
                context.logger.logAndShowError(
                    "Error encountered while setting up Coder",
                    "Failed to set up Coder: ${ex.message}",
                    ex
                )
            } finally {
                firstRun = false
            }
        }

        // Login flow.
        return CoderSetupWizardPage.deploymentUrlStep(
            context, settingsPage,
            onConnect = onConnect,
            onTokenRefreshed = ::onTokenRefreshed,
        )
    }

    /**
     * Auto-login only on the first run when stored credentials or mTLS auth can be used.
     */
    private fun shouldDoAutoSetup(): Boolean = firstRun && (canAutoLogin() || !context.settingsStore.requiresTokenAuth)

    fun canAutoLogin(): Boolean = autoSetupCredentials(context.deploymentUrl) != null

    private val onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager> = SuspendBiConsumer { client, cli ->
        router.finishSignIn(currentCoroutineContext().job) { startSession(client, cli) }
    }

    private fun startSession(client: CoderRestClient, cli: CoderCLIManager) {
        // Store the URL and token for use next time.
        storeSession(client)
        resetConnection()
        this.client = client
        this.cli = cli
        lastEnvironments.forEach { it.updateClientAndCli(client, cli) }
        environments.showLoadingMessage()
        if (context.settingsStore.useAppNameAsTitle) {
            context.logger.info("Displaying ${client.appName} as main page title")
            coderHeaderPage.setTitle(context.i18n.pnotr(client.appName))
        } else {
            context.logger.info("Displaying ${client.url} as main page title")
            coderHeaderPage.setTitle(context.i18n.pnotr(client.url.toString()))
        }
        accountDropdownField.labelState.update {
            context.i18n.pnotr(client.me.username)
        }
        accountDropdownField.visibility.update { true }
        coderHeaderPage.resetFilter()
        context.cs.launch(CoroutineName("Load Templates")) { coderHeaderPage.reloadTemplates() }
        pollJob = poll(client, cli)
        context.logger.info("Workspace poll job with name ${pollJob.toString()} was created")
    }

    private fun MutableStateFlow<LoadableState<List<CoderRemoteEnvironment>>>.showLoadingMessage() {
        this.update {
            LoadableState.Loading
        }
    }
}

private fun Throwable.hasInvalidCoderIdentifierCause(): Boolean =
    this is InvalidCoderIdentifierException || cause is InvalidCoderIdentifierException

private data class ResolvedDeploymentParameters(
    val url: URL,
    val token: String?,
) {
    override fun toString(): String = "ResolvedDeploymentParameters(url=$url)"
}
