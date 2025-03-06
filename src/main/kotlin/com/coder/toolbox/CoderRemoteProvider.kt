package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.logger.CoderLoggerFactory
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.services.CoderSecretsService
import com.coder.toolbox.services.CoderSettingsService
import com.coder.toolbox.settings.CoderSettings
import com.coder.toolbox.settings.Source
import com.coder.toolbox.util.DialogUi
import com.coder.toolbox.util.LinkHandler
import com.coder.toolbox.util.toQueryParameters
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.CoderSettingsPage
import com.coder.toolbox.views.ConnectPage
import com.coder.toolbox.views.NewEnvironmentPage
import com.coder.toolbox.views.SignInPage
import com.coder.toolbox.views.TokenPage
import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.remoteDev.RemoteProviderEnvironment
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import com.jetbrains.toolbox.api.ui.components.AccountDropdownField as DropDownMenu
import com.jetbrains.toolbox.api.ui.components.AccountDropdownField as dropDownFactory

class CoderRemoteProvider(
    private val serviceLocator: ServiceLocator,
    private val httpClient: OkHttpClient,
) : RemoteProvider("Coder") {
    private val logger = CoderLoggerFactory.getLogger(javaClass)

    private val ui: ToolboxUi = serviceLocator.getService(ToolboxUi::class.java)
    private val coroutineScope: CoroutineScope = serviceLocator.getService(CoroutineScope::class.java)
    private val settingsStore: PluginSettingsStore = serviceLocator.getService(PluginSettingsStore::class.java)
    private val secretsStore: PluginSecretStore = serviceLocator.getService(PluginSecretStore::class.java)
    private val i18n = serviceLocator.getService(LocalizableStringFactory::class.java)

    // Current polling job.
    private var pollJob: Job? = null
    private var lastEnvironments: Set<CoderRemoteEnvironment>? = null

    // Create our services from the Toolbox ones.
    private val settingsService = CoderSettingsService(settingsStore)
    private val settings: CoderSettings = CoderSettings(settingsService)
    private val secrets: CoderSecretsService = CoderSecretsService(secretsStore)
    private val settingsPage: CoderSettingsPage =
        CoderSettingsPage(serviceLocator, settingsService, i18n.ptrl("Coder Settings"))
    private val dialogUi = DialogUi(serviceLocator, settings)
    private val linkHandler = LinkHandler(serviceLocator, settings, httpClient, dialogUi)

    // The REST client, if we are signed in
    private var client: CoderRestClient? = null

    // If we have an error in the polling we store it here before going back to
    // sign-in page, so we can display it there.  This is mainly because there
    // does not seem to be a mechanism to show errors on the environment list.
    private var pollError: Exception? = null

    // On the first load, automatically log in if we can.
    private var firstRun = true

    override val environments: MutableStateFlow<LoadableState<List<RemoteProviderEnvironment>>> = MutableStateFlow(
        LoadableState.Value(emptyList())
    )

    /**
     * With the provided client, start polling for workspaces.  Every time a new
     * workspace is added, reconfigure SSH using the provided cli (including the
     * first time).
     */
    private fun poll(client: CoderRestClient, cli: CoderCLIManager): Job = coroutineScope.launch {
        while (isActive) {
            try {
                logger.debug("Fetching workspace agents from {}", client.url)
                val resolvedEnvironments = client.workspaces().flatMap { ws ->
                    // Agents are not included in workspaces that are off
                    // so fetch them separately.
                    when (ws.latestBuild.status) {
                        WorkspaceStatus.RUNNING -> ws.latestBuild.resources
                        else -> emptyList()
                    }.ifEmpty {
                        client.resources(ws)
                    }.flatMap { resource ->
                        resource.agents?.distinctBy {
                            // There can be duplicates with coder_agent_instance.
                            // TODO: Can we just choose one or do they hold
                            //       different information?
                            it.name
                        }?.map { agent ->
                            // If we have an environment already, update that.
                            val env = CoderRemoteEnvironment(serviceLocator, client, ws, agent, coroutineScope)
                            lastEnvironments?.firstOrNull { it == env }?.let {
                                it.update(ws, agent)
                                it
                            } ?: env
                        } ?: emptyList()
                    }
                }.toSet()

                // In case we logged out while running the query.
                if (!isActive) {
                    return@launch
                }

                // Reconfigure if a new environment is found.
                // TODO@JB: Should we use the add/remove listeners instead?
                val newEnvironments = lastEnvironments
                    ?.let { resolvedEnvironments.subtract(it) }
                    ?: resolvedEnvironments
                if (newEnvironments.isNotEmpty()) {
                    logger.info("Found new environment(s), reconfiguring CLI: {}", newEnvironments)
                    cli.configSsh(newEnvironments.map { it.name }.toSet())
                }

                environments.update {
                    LoadableState.Value(resolvedEnvironments.toList())
                }

                lastEnvironments = resolvedEnvironments
            } catch (_: CancellationException) {
                logger.debug("{} polling loop canceled", client.url)
                break
            } catch (ex: Exception) {
                logger.info("setting exception $ex")
                pollError = ex
                logout()
                break
            }
            // TODO: Listening on a web socket might be better?
            delay(5.seconds)
        }
    }

    /**
     * Stop polling, clear the client and environments, then go back to the
     * first page.
     */
    private fun logout() {
        // Keep the URL and token to make it easy to log back in, but set
        // rememberMe to false so we do not try to automatically log in.
        secrets.rememberMe = "false"
        close()
    }

    /**
     * A dropdown that appears at the top of the environment list to the right.
     */
    override fun getAccountDropDown(): DropDownMenu? {
        val username = client?.me?.username
        if (username != null) {
            return dropDownFactory(i18n.pnotr(username), { logout() })
        }
        return null
    }

    override val additionalPluginActions: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            Action(i18n.ptrl("Settings")) {
                ui.showUiPage(settingsPage)
            },
        )
    )

    /**
     * Cancel polling and clear the client and environments.
     *
     * Called as part of our own logout but it is unclear where it is called by
     * Toolbox.  Maybe on uninstall?
     */
    override fun close() {
        pollJob?.cancel()
        client = null
        lastEnvironments = null
        environments.value = LoadableState.Value(emptyList())
    }

    override val svgIcon: SvgIcon =
        SvgIcon(this::class.java.getResourceAsStream("/icon.svg")?.readAllBytes() ?: byteArrayOf())

    override val noEnvironmentsSvgIcon: SvgIcon? =
        SvgIcon(this::class.java.getResourceAsStream("/icon.svg")?.readAllBytes() ?: byteArrayOf())

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
    override fun getNewEnvironmentUiPage(): UiPage =
        NewEnvironmentPage(serviceLocator, i18n.pnotr(getDeploymentURL()?.first ?: ""))

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
    override fun setVisible(visibilityState: ProviderVisibilityState) {}

    /**
     * Handle incoming links (like from the dashboard).
     */
    override suspend fun handleUri(uri: URI) {
        val params = uri.toQueryParameters()
        coroutineScope.launch {
            val name = linkHandler.handle(params)
            // TODO@JB: Now what?  How do we actually connect this workspace?
            logger.debug("External request for {}: {}", name, uri)
        }
    }

    /**
     * Make Toolbox ask for the page again.  Use any time we need to change the
     * root page (for example, sign-in or the environment list).
     *
     * When moving between related pages, instead use ui.showUiPage() and
     * ui.hideUiPage() which stacks and has built-in back navigation, rather
     * than using multiple root pages.
     */
    private fun goToEnvironmentsPage() {
        serviceLocator.getService(EnvironmentUiPageManager::class.java).showPluginEnvironmentsPage()
    }

    /**
     * Return the sign-in page if we do not have a valid client.

     * Otherwise return null, which causes Toolbox to display the environment
     * list.
     */
    override fun getOverrideUiPage(): UiPage? {
        // Show sign in page if we have not configured the client yet.
        if (client == null) {
            // When coming back to the application, authenticate immediately.
            val autologin = firstRun && secrets.rememberMe == "true"
            var autologinEx: Exception? = null
            secrets.lastToken.let { lastToken ->
                secrets.lastDeploymentURL.let { lastDeploymentURL ->
                    if (autologin && lastDeploymentURL.isNotBlank() && (lastToken.isNotBlank() || !settings.requireTokenAuth)) {
                        try {
                            return createConnectPage(URL(lastDeploymentURL), lastToken)
                        } catch (ex: Exception) {
                            autologinEx = ex
                        }
                    }
                }
            }
            firstRun = false

            // Login flow.
            val signInPage =
                SignInPage(serviceLocator, i18n.ptrl("Sign In to Coder"), getDeploymentURL()) { deploymentURL ->
                ui.showUiPage(
                    TokenPage(
                        serviceLocator,
                        i18n.ptrl("Enter your token"),
                        deploymentURL,
                        getToken(deploymentURL)
                    ) { selectedToken ->
                        ui.showUiPage(createConnectPage(deploymentURL, selectedToken))
                    },
                )
            }

            // We might have tried and failed to automatically log in.
            autologinEx?.let { signInPage.notify("Error logging in", it) }
            // We might have navigated here due to a polling error.
            pollError?.let { signInPage.notify("Error fetching workspaces", it) }

            return signInPage
        }
        return null
    }

    /**
     * Create a connect page that starts polling and resets the UI on success.
     */
    private fun createConnectPage(deploymentURL: URL, token: String?): ConnectPage = ConnectPage(
        serviceLocator,
        deploymentURL,
        token,
        settings,
        httpClient,
        i18n.ptrl("Connecting to Coder"),
        ::goToEnvironmentsPage,
    ) { client, cli ->
        // Store the URL and token for use next time.
        secrets.lastDeploymentURL = client.url.toString()
        secrets.lastToken = client.token ?: ""
        // Currently we always remember, but this could be made an option.
        secrets.rememberMe = "true"
        this.client = client
        pollError = null
        pollJob?.cancel()
        pollJob = poll(client, cli)
        goToEnvironmentsPage()
    }

    /**
     * Try to find a token.
     *
     * Order of preference:
     *
     * 1. Last used token, if it was for this deployment.
     * 2. Token on disk for this deployment.
     * 3. Global token for Coder, if it matches the deployment.
     */
    private fun getToken(deploymentURL: URL): Pair<String, Source>? = secrets.lastToken.let {
        if (it.isNotBlank() && secrets.lastDeploymentURL == deploymentURL.toString()) {
            it to Source.LAST_USED
        } else {
            settings.token(deploymentURL)
        }
    }

    /**
     * Try to find a URL.
     *
     * In order of preference:
     *
     * 1. Last used URL.
     * 2. URL in settings.
     * 3. CODER_URL.
     * 4. URL in global cli config.
     */
    private fun getDeploymentURL(): Pair<String, Source>? = secrets.lastDeploymentURL.let {
        if (it.isNotBlank()) {
            it to Source.LAST_USED
        } else {
            settings.defaultURL()
        }
    }
}
