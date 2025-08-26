package com.coder.toolbox

import com.coder.toolbox.browser.browse
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.util.CoderProtocolHandler
import com.coder.toolbox.util.DialogUi
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.waitForTrue
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.Action
import com.coder.toolbox.views.CoderCliSetupWizardPage
import com.coder.toolbox.views.CoderSettingsPage
import com.coder.toolbox.views.NewEnvironmentPage
import com.coder.toolbox.views.state.CoderCliSetupContext
import com.coder.toolbox.views.state.CoderCliSetupWizardState
import com.coder.toolbox.views.state.WizardStep
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon.IconType
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.ui.actions.ActionDelimiter
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

    private val triggerSshConfig = Channel<Boolean>(Channel.CONFLATED)
    private val triggerProviderVisible = Channel<Boolean>(Channel.CONFLATED)
    private val settingsPage: CoderSettingsPage = CoderSettingsPage(context, triggerSshConfig)
    private val dialogUi = DialogUi(context)

    // The REST client, if we are signed in
    private var client: CoderRestClient? = null

    // On the first load, automatically log in if we can.
    private var firstRun = true
    private val isInitialized: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val coderHeaderPage = NewEnvironmentPage(context.i18n.pnotr(context.deploymentUrl.toString()))
    private val visibilityState = MutableStateFlow(
        ProviderVisibilityState(
            applicationVisible = false,
            providerVisible = false
        )
    )
    private val linkHandler = CoderProtocolHandler(context, dialogUi, settingsPage, visibilityState, isInitialized)

    override val loadingEnvironmentsDescription: LocalizableString = context.i18n.ptrl("Loading workspaces...")
    override val environments: MutableStateFlow<LoadableState<List<CoderRemoteEnvironment>>> = MutableStateFlow(
        LoadableState.Loading
    )

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
                                val env = CoderRemoteEnvironment(context, client, cli, ws, agent)
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

                    // Reconfigure if environments changed.
                    if (lastEnvironments.size != resolvedEnvironments.size || lastEnvironments != resolvedEnvironments) {
                        context.logger.info("Workspaces have changed, reconfiguring CLI: $resolvedEnvironments")
                        cli.configSsh(resolvedEnvironments.map { it.asPairOfWorkspaceAndAgent() }.toSet())
                    }

                    environments.update {
                        LoadableState.Value(resolvedEnvironments.toList())
                    }
                    if (!isInitialized.value) {
                        context.logger.info("Environments for ${client.url} are now initialized")
                        isInitialized.update {
                            true
                        }
                    }
                    lastEnvironments.apply {
                        clear()
                        addAll(resolvedEnvironments.sortedBy { it.id })
                    }

                    if (WorkspaceConnectionManager.shouldEstablishWorkspaceConnections) {
                        WorkspaceConnectionManager.allConnected().forEach { wsId ->
                            val env = lastEnvironments.firstOrNull() { it.id == wsId }
                            if (env != null && !env.isConnected()) {
                                context.logger.info("Establishing lost SSH connection for workspace with id $wsId")
                                if (!env.startSshConnection()) {
                                    context.logger.info("Can't establish lost SSH connection for workspace with id $wsId")
                                }
                            }
                        }
                        WorkspaceConnectionManager.reset()
                    }

                    WorkspaceConnectionManager.collectStatuses(lastEnvironments)
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
                            WorkspaceConnectionManager.shouldEstablishWorkspaceConnections = true
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
     * Stop polling, clear the client and environments, then go back to the
     * first page.
     */
    private fun logout() {
        WorkspaceConnectionManager.reset()
        close()
    }

    /**
     * A dropdown that appears at the top of the environment list to the right.
     */
    override fun getAccountDropDown(): DropDownMenu? {
        val username = client?.me?.username
        if (username != null) {
            return dropDownFactory(context.i18n.pnotr(username)) {
                logout()
                context.envPageManager.showPluginEnvironmentsPage()
            }
        }
        return null
    }

    override val additionalPluginActions: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            Action(context.i18n.ptrl("Create workspace")) {
                context.cs.launch(CoroutineName("Create Workspace Action")) {
                    context.desktop.browse(client?.url?.withPath("/templates").toString()) {
                        context.ui.showErrorInfoPopup(it)
                    }
                }
            },
            CoderDelimiter(context.i18n.pnotr("")),
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
        client = null
        CoderCliSetupWizardState.goToFirstStep()
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
            linkHandler.handle(
                uri,
                shouldDoAutoSetup()
            ) { restClient, cli ->
                // stop polling and de-initialize resources
                close()
                isInitialized.update {
                    false
                }
                // start initialization with the new settings
                this@CoderRemoteProvider.client = restClient
                coderHeaderPage.setTitle(context.i18n.pnotr(restClient.url.toString()))

                environments.showLoadingMessage()
                pollJob = poll(restClient, cli)
                isInitialized.waitForTrue()
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
            context.envPageManager.showPluginEnvironmentsPage()
        }
    }

    /**
     * Return the sign-in page if we do not have a valid client.

     * Otherwise, return null, which causes Toolbox to display the environment
     * list.
     */
    override fun getOverrideUiPage(): UiPage? {
        // Show the setup page if we have not configured the client yet.
        if (client == null) {
            // When coming back to the application, initializeSession immediately.
            if (shouldDoAutoSetup()) {
                try {
                    CoderCliSetupContext.apply {
                        url = context.secrets.lastDeploymentURL.toURL()
                        token = context.secrets.lastToken
                    }
                    CoderCliSetupWizardState.goToStep(WizardStep.CONNECT)
                    return CoderCliSetupWizardPage(
                        context, settingsPage, visibilityState,
                        initialAutoSetup = true,
                        jumpToMainPageOnError = false,
                        onConnect = ::onConnect
                    )
                } catch (ex: Exception) {
                    errorBuffer.add(ex)
                } finally {
                    firstRun = false
                }
            }

            // Login flow.
            val setupWizardPage =
                CoderCliSetupWizardPage(context, settingsPage, visibilityState, onConnect = ::onConnect)
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
    private fun shouldDoAutoSetup(): Boolean = firstRun && (context.secrets.canAutoLogin || !settings.requireTokenAuth)

    private fun onConnect(client: CoderRestClient, cli: CoderCLIManager) {
        // Store the URL and token for use next time.
        context.secrets.lastDeploymentURL = client.url.toString()
        context.secrets.lastToken = client.token ?: ""
        context.secrets.storeTokenFor(client.url, context.secrets.lastToken)
        context.logger.info("Deployment URL and token were stored and will be available for automatic connection")
        this.client = client
        pollJob?.cancel()
        context.logger.info("Previous poll job was canceled")
        environments.showLoadingMessage()
        coderHeaderPage.setTitle(context.i18n.pnotr(client.url.toString()))
        context.logger.info("Displaying ${client.url} in the UI")
        pollJob = poll(client, cli)
        context.logger.info("Workspace poller job created with reference $pollJob")
        context.envPageManager.showPluginEnvironmentsPage()
    }

    private fun MutableStateFlow<LoadableState<List<CoderRemoteEnvironment>>>.showLoadingMessage() {
        this.update {
            LoadableState.Loading
        }
    }
}

private class CoderDelimiter(override val label: LocalizableString) : ActionDelimiter