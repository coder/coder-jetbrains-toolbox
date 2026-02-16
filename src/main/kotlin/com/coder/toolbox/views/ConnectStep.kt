package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.oauth.OAuthTokenResponse
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.CoderSetupWizardContext
import com.coder.toolbox.views.state.CoderSetupWizardState
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

private const val USER_HIT_THE_BACK_BUTTON = "User hit the back button"

/**
 * A page that connects a REST client and cli to Coder.
 */
class ConnectStep(
    private val context: CoderToolboxContext,
    private val shouldAutoLogin: StateFlow<Boolean>,
    private val jumpToMainPageOnError: Boolean,
    private val connectSynchronously: Boolean,
    visibilityState: StateFlow<ProviderVisibilityState>,
    private val refreshWizard: () -> Unit,
    private val onConnect: suspend (client: CoderRestClient, cli: CoderCLIManager) -> Unit,
    private val onTokenRefreshed: (suspend (token: OAuthTokenResponse) -> Unit)? = null
) : WizardStep {
    private var signInJob: Job? = null

    private val statusField = LabelField(context.i18n.pnotr(""))
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))
    private val errorReporter = ErrorReporter.create(context, visibilityState, this.javaClass)

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(statusField),
        RowGroup.RowField(errorField)
    )

    override fun onVisible() {
        errorReporter.flush()
        errorField.textState.update {
            context.i18n.pnotr("")
        }

        if (context.settingsStore.requiresTokenAuth && CoderSetupWizardContext.isNotReadyForAuth()) {
            errorField.textState.update {
                context.i18n.pnotr("URL and token were not properly configured. Please go back and provide a proper URL and token!")
            }
            return
        }

        statusField.textState.update { context.i18n.pnotr("Connecting to ${CoderSetupWizardContext.url?.host ?: "unknown host"}...") }
        connect()
    }

    /**
     * Try connecting to Coder with the provided URL and token.
     */
    private fun connect() {
        val url = CoderSetupWizardContext.url
        if (url == null) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return
        }

        if (context.settingsStore.requiresTokenAuth && !CoderSetupWizardContext.hasToken() && !CoderSetupWizardContext.hasOAuthSession()) {
            errorField.textState.update { context.i18n.ptrl("Token is required") }
            return
        }

        // Capture the host name early for error reporting
        val hostName = url.host

        // Cancel previous job regardless of the new mode
        signInJob?.cancel()

        // 1. Extract the logic into a reusable suspend lambda
        val connectionLogic: suspend CoroutineScope.() -> Unit = {
            try {
                context.logger.info("Setting up the HTTP client...")
                val client = CoderRestClient(
                    context,
                    url,
                    if (context.settingsStore.requiresTokenAuth) CoderSetupWizardContext.token else null,
                    if (context.settingsStore.requiresTokenAuth && CoderSetupWizardContext.hasOAuthSession()) CoderSetupWizardContext.oauthSession!!.copy() else null,
                    PluginManager.pluginInfo.version,
                    onTokenRefreshed
                )
                // allows interleaving with the back/cancel action
                yield()
                client.initializeSession()
                logAndReportProgress("Checking Coder CLI...")
                val cli = ensureCLI(
                    context, client.url,
                    client.buildVersion
                ) { progress ->
                    statusField.textState.update { (context.i18n.pnotr(progress)) }
                }
                // We only need to log in if we are using token-based auth.
                if (context.settingsStore.requiresTokenAuth) {
                    logAndReportProgress("Configuring Coder CLI...")
                    // allows interleaving with the back/cancel action
                    yield()
                    if (CoderSetupWizardContext.hasOAuthSession()) {
                        cli.login(CoderSetupWizardContext.oauthSession!!.tokenResponse!!.accessToken)
                    } else {
                        cli.login(client.token!!)
                    }
                }
                logAndReportProgress("Successfully configured ${hostName}...")
                // allows interleaving with the back/cancel action
                yield()
                context.logger.info("Connection setup done, initializing the workspace poller...")
                onConnect(client, cli)

                CoderSetupWizardContext.reset()
                CoderSetupWizardState.goToFirstStep()
                context.envPageManager.showPluginEnvironmentsPage()
            } catch (ex: CancellationException) {
                if (ex.message != USER_HIT_THE_BACK_BUTTON) {
                    errorReporter.report("Connection to $hostName was configured", ex)
                    handleNavigation()
                    refreshWizard()
                }
            } catch (ex: Exception) {
                errorReporter.report("Failed to configure $hostName", ex)
                handleNavigation()
                refreshWizard()
            }
        }

        // 2. Choose the execution strategy based on the flag
        if (connectSynchronously) {
            // Blocks the current thread until connectionLogic completes
            runBlocking(CoroutineName("Synchronous Http and CLI Setup")) {
                connectionLogic()
            }
        } else {
            // Runs asynchronously using the context's scope
            signInJob = context.cs.launch(CoroutineName("Async Http and CLI Setup"), block = connectionLogic)
        }
    }

    private fun logAndReportProgress(msg: String) {
        context.logger.info(msg)
        statusField.textState.update { context.i18n.pnotr(msg) }
    }

    /**
     * Handle navigation logic for both errors and back button
     */
    private fun handleNavigation() {
        if (shouldAutoLogin.value) {
            CoderSetupWizardContext.reset()
            if (jumpToMainPageOnError) {
                context.popupPluginMainPage()
            } else {
                CoderSetupWizardState.goToFirstStep()
            }
        } else {
            if (context.settingsStore.requiresTokenAuth) {
                CoderSetupWizardState.goToPreviousStep()
            } else {
                CoderSetupWizardState.goToFirstStep()
            }
        }
    }

    override suspend fun onNext(): Boolean {
        return false
    }

    override fun onBack() {
        try {
            context.logger.info("Back button was pressed, cancelling in-progress connection setup...")
            signInJob?.cancel(CancellationException(USER_HIT_THE_BACK_BUTTON))
        } finally {
            handleNavigation()
        }
    }
}
