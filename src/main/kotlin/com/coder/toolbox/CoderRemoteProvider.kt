package com.coder.toolbox

import com.coder.toolbox.browser.browse
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.feed.IdeFeedManager
import com.coder.toolbox.oauth.OAuth2Client
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.ex.OAuthTokenResponseException
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.util.CoderProtocolHandler
import com.coder.toolbox.util.DialogUi
import com.coder.toolbox.util.TOKEN
import com.coder.toolbox.util.URL
import com.coder.toolbox.util.WebUrlValidationResult.Invalid
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.net.URI
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import com.jetbrains.toolbox.api.ui.components.AccountDropdownField as dropDownFactory

private val POLL_INTERVAL = 5.seconds
private const val CAN_T_HANDLE_URI_TITLE = "Can't handle URI"
private const val FAILED_TO_HANDLE_OAUTH2_TITLE = "Failed to handle OAuth2 request"

@OptIn(ExperimentalCoroutinesApi::class)
class CoderRemoteProvider(
    private val context: CoderToolboxContext,
) : RemoteProvider("Coder") {
    // Current polling job.
    private var pollJob: Job? = null
    internal val lastEnvironments = mutableListOf<CoderRemoteEnvironment>()

    private val sshConfigTrigger = Channel<Boolean>(Channel.CONFLATED)
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
        logout()
        context.envPageManager.showPluginEnvironmentsPage(false)
    }.apply {
        visibility.update { false }
    }

    private val router = PageRouter()

    /**
     * With the provided client, start polling for workspaces.  Every time a new
     * workspace is added, reconfigure SSH using the provided cli (including the
     * first time).
     */
    private fun poll(client: CoderRestClient, cli: CoderCLIManager): Job =
        context.cs.launch(CoroutineName("Workspace Poller")) {
            var lastPollTime = TimeSource.Monotonic.markNow()
            while (isActive) {
                try {
                    context.logger.debug("Fetching workspace agents from ${client.url}")
                    val resolvedEnvironments = resolveWorkspaceEnvironments(client, cli)

                    // In case we logged out while running the query.
                    if (!isActive) {
                        return@launch
                    }

                    // Toolbox closes removed environments without firing their
                    // disconnect hooks, so stop their background work before dropping them.
                    lastEnvironments.filter { it !in resolvedEnvironments }.forEach { it.dispose() }

                    // Reconfigure if environments changed.
                    if (lastEnvironments.size != resolvedEnvironments.size || lastEnvironments != resolvedEnvironments) {
                        context.logger.info("Workspaces have changed, reconfiguring CLI: $resolvedEnvironments")
                        cli.configSsh(resolvedEnvironments.mapNotNull { it.toWorkspaceAgentPairOrNull() }.toSet())
                    }

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
                    context.logger.debug("${client.url} polling loop canceled")
                    break
                } catch (ex: Exception) {
                    val elapsed = lastPollTime.elapsedNow()
                    if (elapsed > POLL_INTERVAL * 2) {
                        context.logger.info("wake-up from an OS sleep was detected")
                    } else {
                        if ((ex is APIResponseException && ex.isTokenExpired) || ex is OAuthTokenResponseException) {
                            close()
                            context.envPageManager.showPluginEnvironmentsPage(false)
                            context.logAndShowError(
                                "Error encountered while setting up Coder",
                                "Your Coder session has expired. Please re-authenticate and try again.",
                                ex
                            )
                            break
                        }
                        context.logger.error(ex, "workspace polling error encountered")
                    }
                }

                select {
                    onTimeout(POLL_INTERVAL) {
                        context.logger.debug("workspace poller waked up by the $POLL_INTERVAL timeout")
                    }
                    sshConfigTrigger.onReceive { shouldTrigger ->
                        if (shouldTrigger) {
                            context.logger.debug("workspace poller waked up because it should reconfigure the ssh configurations")
                            cli.configSsh(lastEnvironments.mapNotNull { it.toWorkspaceAgentPairOrNull() }.toSet())
                        }
                    }
                    workspaceRefreshTrigger.onReceive { shouldTrigger ->
                        if (shouldTrigger) {
                            context.logger.debug("workspace poller waked up to fetch workspaces from the latest header settings")
                        }
                    }
                    providerVisibleTrigger.onReceive { isCoderProviderVisible ->
                        if (isCoderProviderVisible) {
                            context.logger.debug("workspace poller waked up by Coder Toolbox which is currently visible, fetching latest workspace statuses")
                        }
                    }
                }
                lastPollTime = TimeSource.Monotonic.markNow()
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

            val resources = ws.latestBuild.resources.ifEmpty {
                client.resources(ws)
            }
            resources
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

    /**
     * Stop polling, clear the client and environments, then go back to the
     * first page.
     */
    private fun logout() {
        context.logger.info("Logging out ${client?.me?.username}...")
        close()
        context.logger.info("User ${client?.me?.username} logged out successfully")
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
        softClose()
        client = null
        cli = null
        lastEnvironments.forEach { it.dispose() }
        lastEnvironments.clear()
        environments.value = LoadableState.Value(emptyList())
        isInitialized.update { false }
        accountDropdownField.visibility.update { false }
        router.clear()
        context.logger.info("Coder plugin is now closed")
    }

    private fun softClose() {
        pollJob?.let {
            it.cancel()
            context.logger.info("Cancelled workspace poll job ${pollJob.toString()}")
        }
        pollJob = null
        client?.let {
            it.close()
            context.logger.info("REST API client closed and resources released")
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
            if (uri.toString().startsWith("jetbrains://gateway/com.coder.toolbox/auth")) {
                handleOAuthUri(uri)
                return
            }

            val params = uri.toQueryParameters()
            if (params.isEmpty()) {
                // probably a plugin installation scenario
                context.logAndShowInfo("URI will not be handled", "No query parameters were provided")
                return
            }
            context.logger.info("Handling $uri...")
            val newUrl = resolveDeploymentUrl(params)?.toURL() ?: return
            val newToken = if (context.settingsStore.requiresMTlsAuth) null else resolveToken(params) ?: return
            if (sameUrl(newUrl, client?.url)) {
                coderHeaderPage.isBusy.update { true }
                try {
                    val activeSession = if (context.settingsStore.requiresTokenAuth) {
                        newToken?.let {
                            refreshSession(newUrl, it)
                        } ?: (this.client!! to this.cli!!)
                    } else {
                        this.client!! to this.cli!!
                    }
                    handleLink(params, newUrl, activeSession.first, activeSession.second)
                } finally {
                    coderHeaderPage.isBusy.update { false }
                }
            } else {
                // Different URL - we need a new connection. Tear down any
                // in-flight wizard, install a fresh one on the router, and let
                // showPluginEnvironmentsPage() pull it through getOverrideUiPage.
                val credentials = newToken?.let { Credentials.Token(it) } ?: Credentials.MTls
                val wizard = CoderSetupWizardPage.connectStep(
                    context, settingsPage,
                    url = newUrl,
                    credentials = credentials,
                    onConnect = onConnect.andThen(deferredLinkHandler(params, newUrl)),
                    onTokenRefreshed = ::onTokenRefreshed,
                )
                router.navigate(wizard)
                context.popupPluginMainPage()
            }
        } catch (ex: Exception) {
            val textError = if (ex is APIResponseException) {
                if (!ex.reason.isNullOrBlank()) {
                    ex.reason
                } else ex.message
            } else ex.message
            context.logAndShowError(
                "Error encountered while handling Coder URI",
                textError ?: ""
            )
        } finally {
            firstRun = false
        }
    }

    private suspend fun handleOAuthUri(uri: URI) {
        val params = uri.toQueryParameters()

        // RFC 6749 §4.1.2.1 (also covers RFC 7636 §4.4.1 PKCE errors): the authorization
        // server redirects back with `error` when authorization fails (e.g. access_denied,
        // invalid_request, unsupported_response_type, server_error, ...).
        val error = params["error"]
        if (error != null) {
            val description = params["error_description"]?.let { " - $it" } ?: ""
            return context.logAndShowError(
                FAILED_TO_HANDLE_OAUTH2_TITLE,
                "OAuth2 authorization error: $error$description"
            )
        }

        if (!router.hasActiveWizard) {
            return context.logAndShowError(
                FAILED_TO_HANDLE_OAUTH2_TITLE,
                "OAuth2 callback arrived but the setup wizard is no longer active"
            )
        }
        val pendingOAuthConnection = router.pendingOAuthConnection ?: return context.logAndShowError(
            FAILED_TO_HANDLE_OAUTH2_TITLE,
            "OAuth2 callback arrived but no OAuth session was started"
        )
        params["state"]?.takeIf { it == pendingOAuthConnection.session.state }
            ?: return context.logAndShowError(
                FAILED_TO_HANDLE_OAUTH2_TITLE,
                "Server responded back with an invalid state that does not match the initial authorization state sent to the server"
            )

        val code = params["code"] ?: return context.logAndShowError(
            FAILED_TO_HANDLE_OAUTH2_TITLE,
            "OAuth2 server did not respond back with an access token"
        )
        // before going forward we check to make sure OAuth is not disabled in the meantime
        if (!context.settingsStore.preferOAuth2IfAvailable) {
            context.logAndShowError(
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
            router.navigate(wizard)

            context.envPageManager.showPluginEnvironmentsPage(false)
            context.ui.showUiPage(wizard)
        } catch (e: Exception) {
            context.logAndShowError("OAuth Error", "Exception during token exchange: ${e.message}", e)
        }
    }

    private suspend fun resolveDeploymentUrl(params: Map<String, String>): String? {
        val deploymentURL = params.url() ?: askUrl()
        if (deploymentURL.isNullOrBlank()) {
            context.logAndShowError(CAN_T_HANDLE_URI_TITLE, "Query parameter \"${URL}\" is missing from URI")
            return null
        }
        val validationResult = deploymentURL.validateStrictWebUrl()
        if (validationResult is Invalid) {
            context.logAndShowError(CAN_T_HANDLE_URI_TITLE, "\"$URL\" is invalid: ${validationResult.reason}")
            return null
        }
        return deploymentURL
    }

    private suspend fun resolveToken(params: Map<String, String>): String? {
        val token = params.token()
        if (token.isNullOrBlank()) {
            context.logAndShowError(CAN_T_HANDLE_URI_TITLE, "Query parameter \"$TOKEN\" is missing from URI")
            return null
        }
        return token
    }

    private fun sameUrl(first: URL, second: URL?): Boolean = first.toURI().normalize() == second?.toURI()?.normalize()

    private suspend fun refreshSession(url: URL, token: String): Pair<CoderRestClient, CoderCLIManager> {
        context.logger.info("Stopping workspace polling and re-initializing the http client and cli with a new token")
        softClose()
        val newRestClient = CoderRestClient(
            context,
            url,
            token,
            null,
            PluginManager.pluginInfo.version,
        ).apply { initializeSession() }
        val newCli = CoderCLIManager(context, url).apply {
            login(token)
        }
        this.client = newRestClient
        this.cli = newCli
        lastEnvironments.forEach { it.updateClientAndCli(newRestClient, newCli) }
        accountDropdownField.labelState.update { context.i18n.pnotr(newRestClient.me.username) }
        accountDropdownField.visibility.update { true }
        coderHeaderPage.resetFilter()
        context.cs.launch(CoroutineName("Load Templates")) { coderHeaderPage.reloadTemplates() }
        pollJob = poll(newRestClient, newCli)
        context.logger.info("Workspace poll job with name ${pollJob.toString()} was created while handling URI")
        return newRestClient to newCli
    }

    private suspend fun askUrl(): String? {
        context.popupPluginMainPage()
        return dialogUi.ask(
            context.i18n.ptrl("Deployment URL"),
            context.i18n.ptrl("Enter the full URL of your Coder deployment")
        )
    }

    /**
     * Return the sign-in page if we do not have a valid client.

     * Otherwise, return null, which causes Toolbox to display the environment
     * list.
     */
    override fun getOverrideUiPage(): UiPage? {
        // Show the setup wizard if one is already scheduled.
        router.activePage?.let { return it }

        // Let the default workspace UI render if the HTTP client is initialized.
        if (client != null) return null

        // Otherwise, schedule our own setup wizard.
        return router.getOrCreate { buildSetupWizard() }
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
                )
            } catch (ex: Exception) {
                context.logAndShowError(
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

    private fun autoSetupCredentials(url: URL): Credentials? {
        if (context.settingsStore.requiresMTlsAuth) return Credentials.MTls

        val tokenCredentials = context.secrets.apiTokenFor(url)
            ?.takeIf { it.isNotBlank() }
            ?.let { Credentials.Token(it) }

        if (!context.settingsStore.preferOAuth2IfAvailable) return tokenCredentials

        return context.secrets.oauthSessionFor(url.toString())?.let {
            Credentials.OAuth(it.toSessionContext())
        } ?: tokenCredentials
    }

    private suspend fun onTokenRefreshed(url: URL, oauthSessionCtx: CoderOAuthSessionContext) {
        oauthSessionCtx.tokenResponse?.accessToken?.let { cli?.login(it) }
        context.secrets.storeOAuthFor(url.toString(), oauthSessionCtx)
    }

    private val onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager> = SuspendBiConsumer { client, cli ->
        // Store the URL and token for use next time.
        close()
        context.settingsStore.updateLastUsedUrl(client.url)
        if (context.settingsStore.requiresTokenAuth) {
            if (client.token != null) {
                context.secrets.storeApiTokenFor(client.url, client.token)
            }
            context.logger.info("Deployment URL and token were stored and will be available for automatic connection")
        } else {
            context.logger.info("Deployment URL was stored and will be available for automatic connection")
        }
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
                context.logAndShowError(
                    "Error handling deferred link",
                    ex.message ?: ""
                )
            } finally {
                coderHeaderPage.isBusy.update { false }
            }
        }
    }

    private fun MutableStateFlow<LoadableState<List<CoderRemoteEnvironment>>>.showLoadingMessage() {
        this.update {
            LoadableState.Loading
        }
    }
}
