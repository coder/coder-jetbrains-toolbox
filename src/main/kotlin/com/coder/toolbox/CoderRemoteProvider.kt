package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.settings.SettingSource
import com.coder.toolbox.util.CoderProtocolHandler
import com.coder.toolbox.util.DialogUi
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.CoderSettingsPage
import com.coder.toolbox.views.ConnectPage
import com.coder.toolbox.views.NewEnvironmentPage
import com.coder.toolbox.views.SignInPage
import com.coder.toolbox.views.TokenPage
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon.IconType
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.remoteDev.RemoteProviderEnvironment
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage
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
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import com.jetbrains.toolbox.api.ui.components.AccountDropdownField as DropDownMenu
import com.jetbrains.toolbox.api.ui.components.AccountDropdownField as dropDownFactory

private val POLL_INTERVAL = 5.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CoderRemoteProvider(
    private val context: CoderToolboxContext,
) : RemoteProvider("Coder") {
    // Current polling job.
    private var pollJob: Job? = null
    private val lastEnvironments = mutableSetOf<CoderRemoteEnvironment>()

    private val settings = context.settingsStore.readOnly()

    // Create our services from the Toolbox ones.
    private val triggerSshConfig = Channel<Boolean>(Channel.CONFLATED)
    private val settingsPage: CoderSettingsPage = CoderSettingsPage(context, triggerSshConfig)
    private val dialogUi = DialogUi(context)

    // The REST client, if we are signed in
    private var client: CoderRestClient? = null

    // If we have an error in the polling we store it here before going back to
    // sign-in page, so we can display it there.  This is mainly because there
    // does not seem to be a mechanism to show errors on the environment list.
    private var pollError: Exception? = null

    // On the first load, automatically log in if we can.
    private var firstRun = true
    private val isInitialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var coderHeaderPage = NewEnvironmentPage(context, context.i18n.pnotr(getDeploymentURL()?.first ?: ""))
    private val linkHandler = CoderProtocolHandler(context, dialogUi, isInitialized)
    override val environments: MutableStateFlow<LoadableState<List<RemoteProviderEnvironment>>> = MutableStateFlow(
        LoadableState.Value(emptyList())
    )

    /**
     * With the provided client, start polling for workspaces.  Every time a new
     * workspace is added, reconfigure SSH using the provided cli (including the
     * first time).
     */
    private fun poll(client: CoderRestClient, cli: CoderCLIManager): Job = context.cs.launch {
        var lastPollTime = TimeSource.Monotonic.markNow()
        while (isActive) {
            try {
                context.logger.debug("Fetching workspace agents from ${client.url}")
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
                            val env = CoderRemoteEnvironment(context, client, ws, agent)
                            lastEnvironments.firstOrNull { it == env }?.let {
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
                val newEnvironments = resolvedEnvironments.subtract(lastEnvironments)
                if (newEnvironments.isNotEmpty()) {
                    context.logger.info("Found new environment(s), reconfiguring CLI: $newEnvironments")
                    cli.configSsh(newEnvironments.map { it.name }.toSet())
                }

                environments.update {
                    LoadableState.Value(resolvedEnvironments.toList())
                }
                if (isInitialized.value == false) {
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
            } catch (ex: SocketTimeoutException) {
                val elapsed = lastPollTime.elapsedNow()
                if (elapsed > POLL_INTERVAL * 2) {
                    context.logger.info("wake-up from an OS sleep was detected, going to re-initialize the http client...")
                    client.setupSession()
                } else {
                    context.logger.error(ex, "workspace polling error encountered")
                    pollError = ex
                    logout()
                    break
                }
            } catch (ex: Exception) {
                context.logger.error(ex, "workspace polling error encountered")
                pollError = ex
                logout()
                break
            }

            // TODO: Listening on a web socket might be better?
            select<Unit> {
                onTimeout(POLL_INTERVAL) {
                    context.logger.trace("workspace poller waked up by the $POLL_INTERVAL timeout")
                }
                triggerSshConfig.onReceive { shouldTrigger ->
                    if (shouldTrigger) {
                        context.logger.trace("workspace poller waked up because it should reconfigure the ssh configurations")
                        cli.configSsh(lastEnvironments.map { it.name }.toSet())
                    }
                }
            }
            lastPollTime = TimeSource.Monotonic.markNow()
        }
    }

    /**
     * Stop polling, clear the client and environments, then go back to the
     * first page.
     */
    private fun logout() {
        // Keep the URL and token to make it easy to log back in, but set
        // rememberMe to false so we do not try to automatically log in.
        context.secrets.rememberMe = "false"
        close()
    }

    /**
     * A dropdown that appears at the top of the environment list to the right.
     */
    override fun getAccountDropDown(): DropDownMenu? {
        val username = client?.me?.username
        if (username != null) {
            return dropDownFactory(context.i18n.pnotr(username), { logout() })
        }
        return null
    }

    override val additionalPluginActions: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            Action(context.i18n.ptrl("Settings")) {
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
        pollJob?.cancel()
        client?.close()
        lastEnvironments.clear()
        environments.value = LoadableState.Value(emptyList())
        isInitialized.update { false }
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
    override fun setVisible(visibilityState: ProviderVisibilityState) {}

    /**
     * Handle incoming links (like from the dashboard).
     */
    override suspend fun handleUri(uri: URI) {
        linkHandler.handle(uri, shouldDoAutoLogin()) { restClient, cli ->
            // stop polling and de-initialize resources
            close()
            // start initialization with the new settings
            this@CoderRemoteProvider.client = restClient
            coderHeaderPage = NewEnvironmentPage(context, context.i18n.pnotr(restClient.url.toString()))
            pollJob = poll(restClient, cli)
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
        context.envPageManager.showPluginEnvironmentsPage()
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
            val autologin = shouldDoAutoLogin()
            var autologinEx: Exception? = null
            context.secrets.lastToken.let { lastToken ->
                context.secrets.lastDeploymentURL.let { lastDeploymentURL ->
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
                SignInPage(context, getDeploymentURL()) { deploymentURL ->
                    context.ui.showUiPage(
                        TokenPage(
                            context,
                            deploymentURL,
                            getToken(deploymentURL)
                        ) { selectedToken ->
                            context.ui.showUiPage(createConnectPage(deploymentURL, selectedToken))
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

    private fun shouldDoAutoLogin(): Boolean = firstRun && context.secrets.rememberMe == "true"

    /**
     * Create a connect page that starts polling and resets the UI on success.
     */
    private fun createConnectPage(deploymentURL: URL, token: String?): ConnectPage = ConnectPage(
        context,
        deploymentURL,
        token,
        ::goToEnvironmentsPage,
    ) { client, cli ->
        // Store the URL and token for use next time.
        context.secrets.lastDeploymentURL = client.url.toString()
        context.secrets.lastToken = client.token ?: ""
        // Currently we always remember, but this could be made an option.
        context.secrets.rememberMe = "true"
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
    private fun getToken(deploymentURL: URL): Pair<String, SettingSource>? = context.secrets.lastToken.let {
        if (it.isNotBlank() && context.secrets.lastDeploymentURL == deploymentURL.toString()) {
            it to SettingSource.LAST_USED
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
    private fun getDeploymentURL(): Pair<String, SettingSource>? = context.secrets.lastDeploymentURL.let {
        if (it.isNotBlank()) {
            it to SettingSource.LAST_USED
        } else {
            context.settingsStore.defaultURL()
        }
    }
}
