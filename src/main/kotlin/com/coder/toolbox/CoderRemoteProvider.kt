package com.coder.toolbox

import com.coder.toolbox.browser.browse
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.feed.IdeFeedManager
import com.coder.toolbox.oauth.OAuthTokenResponse
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderHttpClientBuilder
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.util.CoderProtocolHandler
import com.coder.toolbox.util.DialogUi
import com.coder.toolbox.util.TOKEN
import com.coder.toolbox.util.URL
import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.toQueryParameters
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.token
import com.coder.toolbox.util.url
import com.coder.toolbox.util.validateStrictWebUrl
import com.coder.toolbox.util.waitForTrue
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.CoderCliSetupWizardPage
import com.coder.toolbox.views.CoderDelimiter
import com.coder.toolbox.views.CoderSettingsPage
import com.coder.toolbox.views.NewEnvironmentPage
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.CoderSetupWizardContext
import com.coder.toolbox.views.state.CoderSetupWizardState
import com.coder.toolbox.views.state.WizardStep
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon.IconType
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage
import com.squareup.moshi.Moshi
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
import okhttp3.Credentials
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import com.jetbrains.toolbox.api.ui.components.AccountDropdownField as dropDownFactory

private val POLL_INTERVAL = 5.seconds
private const val CAN_T_HANDLE_URI_TITLE = "Can't handle URI"

@OptIn(ExperimentalCoroutinesApi::class)
class CoderRemoteProvider(
    private val context: CoderToolboxContext,
) : RemoteProvider("Coder") {
    // Current polling job.
    private var pollJob: Job? = null
    internal val lastEnvironments = mutableListOf<CoderRemoteEnvironment>()

    private val settings = context.settingsStore.readOnly()

    private val triggerSshConfig = Channel<Boolean>(Channel.CONFLATED)
    private val triggerProviderVisible = Channel<Boolean>(Channel.CONFLATED)
    private val dialogUi = DialogUi(context)

    // The REST client, if we are signed in
    private var client: CoderRestClient? = null
    private var cli: CoderCLIManager? = null

    // On the first load, automatically log in if we can.
    private var firstRun = true

    private val isInitialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val isHandlingUri: AtomicBoolean = AtomicBoolean(false)
    private val coderHeaderPage = NewEnvironmentPage(context.i18n.pnotr(context.deploymentUrl.toString()))
    private val settingsPage: CoderSettingsPage = CoderSettingsPage(context, triggerSshConfig) {
        client?.let { restClient ->
            if (context.settingsStore.useAppNameAsTitle) {
                coderHeaderPage.setTitle(context.i18n.pnotr(restClient.appName))
            } else {
                coderHeaderPage.setTitle(context.i18n.pnotr(restClient.url.toString()))
            }
        }
    }
    private val visibilityState = MutableStateFlow(
        ProviderVisibilityState(
            applicationVisible = false,
            providerVisible = false
        )
    )
    private val linkHandler = CoderProtocolHandler(context, IdeFeedManager(context))

    override val loadingEnvironmentsDescription: LocalizableString = context.i18n.ptrl("Loading workspaces...")
    override val environments: MutableStateFlow<LoadableState<List<CoderRemoteEnvironment>>> = MutableStateFlow(
        LoadableState.Loading
    )
    private val accountDropdownField = dropDownFactory(context.i18n.pnotr("")) {
        logout()
        context.envPageManager.showPluginEnvironmentsPage()
    }

    private val errorBuffer = mutableListOf<Throwable>()

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

                    // Reconfigure if environments changed.
                    if (lastEnvironments.size != resolvedEnvironments.size || lastEnvironments != resolvedEnvironments) {
                        context.logger.info("Workspaces have changed, reconfiguring CLI: $resolvedEnvironments")
                        cli.configSsh(resolvedEnvironments.map { it.asPairOfWorkspaceAndAgent() }.toSet())
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
                        context.logger.error(ex, "workspace polling error encountered")
                        if (ex is APIResponseException && ex.isTokenExpired) {
                            close()
                            context.envPageManager.showPluginEnvironmentsPage()
                            errorBuffer.add(ex)
                            break
                        }
                    }
                }

                select {
                    onTimeout(POLL_INTERVAL) {
                        context.logger.debug("workspace poller waked up by the $POLL_INTERVAL timeout")
                    }
                    triggerSshConfig.onReceive { shouldTrigger ->
                        if (shouldTrigger) {
                            context.logger.debug("workspace poller waked up because it should reconfigure the ssh configurations")
                            cli.configSsh(lastEnvironments.map { it.asPairOfWorkspaceAndAgent() }.toSet())
                        }
                    }
                    triggerProviderVisible.onReceive { isCoderProviderVisible ->
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
     * - If running, uses agents from the latest build resources
     * - If not running, fetches resources separately
     *
     * @return a sorted list of resolved remote environments
     */
    internal suspend fun resolveWorkspaceEnvironments(
        client: CoderRestClient,
        cli: CoderCLIManager,
    ): List<CoderRemoteEnvironment> {
        return client.workspaces().flatMap { ws ->
            // Agents are not included in workspaces that are off
            // so fetch them separately.
            val resources = when (ws.latestBuild.status) {
                WorkspaceStatus.RUNNING -> ws.latestBuild.resources
                else -> emptyList()
            }.ifEmpty {
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
                        } ?: CoderRemoteEnvironment(context, client, cli, ws, agent)
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
        lastEnvironments.clear()
        environments.value = LoadableState.Value(emptyList())
        isInitialized.update { false }
        CoderSetupWizardState.goToFirstStep()
        context.logger.info("Coder plugin is now closed")
    }

    private fun softClose() {
        pollJob?.let {
            it.cancel()
            context.logger.info("Cancelled workspace poll job ${pollJob.toString()}")
        }
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
     * TODO@JB: Supposedly, setting this to false causes the new environment
     *          page to not show but it shows anyway.  For now we have it
     *          displaying the deployment URL, which is actually useful, so if
     *          this changes it would be nice to have a new spot to show the
     *          URL.
     */
    override val canCreateNewEnvironments: Boolean = false

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
        visibilityState.update {
            visibility
        }
        if (visibility.providerVisible) {
            context.cs.launch(CoroutineName("Notify Plugin Visibility")) {
                triggerProviderVisible.send(true)
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
            isHandlingUri.set(true)
            // this switches to the main plugin screen, even
            // if last opened provider was not Coder
            context.envPageManager.showPluginEnvironmentsPage()
            coderHeaderPage.isBusy.update { true }
            context.logger.info("Handling $uri...")
            val newUrl = resolveDeploymentUrl(params)?.toURL() ?: return
            val newToken = if (context.settingsStore.requiresMTlsAuth) null else resolveToken(params) ?: return
            if (sameUrl(newUrl, client?.url)) {
                if (context.settingsStore.requiresTokenAuth) {
                    newToken?.let {
                        refreshSession(newUrl, it)
                    }
                }
            } else {
                CoderSetupWizardContext.apply {
                    url = newUrl
                    token = newToken
                }
                CoderSetupWizardState.goToStep(WizardStep.CONNECT)
                CoderCliSetupWizardPage(
                    context, settingsPage, visibilityState,
                    initialAutoSetup = true,
                    jumpToMainPageOnError = true,
                    connectSynchronously = true,
                    onConnect = ::onConnect,
                    onTokenRefreshed = ::onTokenRefreshed
                ).apply {
                    beforeShow()
                }
            }
            // force the poll loop to run
            triggerProviderVisible.send(true)
            // wait for environments to be populated
            isInitialized.waitForTrue()

            linkHandler.handle(params, newUrl, this.client!!, this.cli!!)
            coderHeaderPage.isBusy.update { false }
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
            context.envPageManager.showPluginEnvironmentsPage()
        } finally {
            coderHeaderPage.isBusy.update { false }
            isHandlingUri.set(false)
            firstRun = false
        }
    }

    private suspend fun handleOAuthUri(uri: URI) {
        val params = uri.toQueryParameters()
        val code = params["code"]
        val state = params["state"]

        if (code != null && state != null && state == CoderSetupWizardContext.oauthSession?.state) {
            if (CoderSetupWizardContext.oauthSession == null) {
                context.logAndShowError(
                    "Failed to handle OAuth code",
                    "We received an OAuth code but our OAuth session is null"
                )
                return
            }
            exchangeOAuthCodeForToken(code)
        }
    }

    private suspend fun exchangeOAuthCodeForToken(code: String) {
        try {
            context.logger.info("Handling OAuth callback...")
            val session = CoderSetupWizardContext.oauthSession ?: return

            // we need to make a POST request to the token endpoint
            val formBodyBuilder = okhttp3.FormBody.Builder()
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("code_verifier", session.tokenCodeVerifier)
                .add("redirect_uri", "jetbrains://gateway/com.coder.toolbox/auth")

            val requestBuilder = okhttp3.Request.Builder()
                .url(session.tokenEndpoint)

            when (session.tokenAuthMethod) {
                TokenEndpointAuthMethod.CLIENT_SECRET_BASIC -> {
                    requestBuilder.header("Authorization", Credentials.basic(session.clientId, session.clientSecret))
                }

                TokenEndpointAuthMethod.CLIENT_SECRET_POST -> {
                    formBodyBuilder.add("client_id", session.clientId)
                    formBodyBuilder.add("client_secret", session.clientSecret)
                }

                else -> {
                    formBodyBuilder.add("client_id", session.clientId)
                }
            }

            val request = requestBuilder
                .post(formBodyBuilder.build())
                .build()

            val response = CoderHttpClientBuilder.default(context)
                .newCall(request)
                .execute()

            if (!response.isSuccessful) {
                context.logAndShowError("OAuth Error", "Failed to exchange code for token")
                return
            }

            val responseBody = response.body?.string() ?: return
            val adapter = Moshi
                .Builder()
                .build()
                .adapter(OAuthTokenResponse::class.java)
            val tokenResponse = adapter.fromJson(responseBody) ?: return

            session.tokenResponse = tokenResponse

            CoderSetupWizardState.goToStep(WizardStep.CONNECT)
            CoderCliSetupWizardPage(
                context, settingsPage, visibilityState,
                initialAutoSetup = true,
                jumpToMainPageOnError = true,
                connectSynchronously = true,
                onConnect = ::onConnect,
                onTokenRefreshed = ::onTokenRefreshed
            ).apply {
                beforeShow()
            }

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
        if (isHandlingUri.get()) {
            return null
        }
        // Show the setup page if we have not configured the client yet.
        if (client == null) {
            // When coming back to the application, initializeSession immediately.
            if (shouldDoAutoSetup()) {
                try {
                    CoderSetupWizardContext.apply {
                        url = context.deploymentUrl
                        token = context.secrets.apiTokenFor(context.deploymentUrl)
                    }
                    CoderSetupWizardState.goToStep(WizardStep.CONNECT)
                    return CoderCliSetupWizardPage(
                        context, settingsPage, visibilityState,
                        initialAutoSetup = true,
                        jumpToMainPageOnError = false,
                        onConnect = ::onConnect,
                        onTokenRefreshed = ::onTokenRefreshed
                    )
                } catch (ex: Exception) {
                    errorBuffer.add(ex)
                } finally {
                    firstRun = false
                }
            }

            // Login flow.
            val setupWizardPage =
                CoderCliSetupWizardPage(
                    context,
                    settingsPage,
                    visibilityState,
                    onConnect = ::onConnect,
                    onTokenRefreshed = ::onTokenRefreshed
                )
            // We might have navigated here due to a polling error.
            errorBuffer.forEach {
                setupWizardPage.notify("Error encountered", it)
            }
            errorBuffer.clear()
            // and now reset the errors, otherwise we show it every time on the screen
            return setupWizardPage
        }
        return null
    }

    /**
     * Auto-login only on first the firs run if there is a url & token configured or the auth
     * should be done via certificates.
     */
    private fun shouldDoAutoSetup(): Boolean = firstRun && (canAutoLogin() || !settings.requiresTokenAuth)

    fun canAutoLogin(): Boolean = !context.secrets.apiTokenFor(context.deploymentUrl).isNullOrBlank()

    private suspend fun onTokenRefreshed(url: URL, oauthSessionCtx: CoderOAuthSessionContext) {
        oauthSessionCtx.tokenResponse?.accessToken?.let { cli?.login(it) }
        context.secrets.storeOAuthFor(url.toString(), oauthSessionCtx)
    }

    private fun onConnect(client: CoderRestClient, cli: CoderCLIManager) {
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
        pollJob = poll(client, cli)
        context.logger.info("Workspace poll job with name ${pollJob.toString()} was created")
    }

    private fun MutableStateFlow<LoadableState<List<CoderRemoteEnvironment>>>.showLoadingMessage() {
        this.update {
            LoadableState.Loading
        }
    }
}