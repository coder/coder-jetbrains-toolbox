package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.oauth.OAuth2Client
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.WizardModel
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
import kotlinx.coroutines.yield
import java.net.URL

private const val USER_HIT_THE_BACK_BUTTON = "User hit the back button"

/**
 * A page that connects a REST client and cli to Coder.
 */
class ConnectStep(
    private val context: CoderToolboxContext,
    private val model: WizardModel,
    private val shouldAutoLogin: StateFlow<Boolean>,
    private val jumpToMainPageOnError: Boolean,
    visibilityState: StateFlow<ProviderVisibilityState>,
    private val refreshWizard: () -> Unit,
    private val onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
    private val onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null
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

        if (context.settingsStore.requiresTokenAuth && model.isNotReadyForAuth()) {
            errorField.textState.update {
                context.i18n.pnotr("URL and token were not properly configured. Please go back and provide a proper URL and token!")
            }
            return
        }

        // Don't launch another connection attempt if one is already in progress.
        if (signInJob?.isActive == true) {
            context.logger.info(">> ConnectStep: connection already in progress, skipping duplicate")
            return
        }

        statusField.textState.update { context.i18n.pnotr("Connecting to ${model.url?.host ?: "unknown host"}...") }
        connect()
    }

    /**
     * Try connecting to Coder with the provided URL and token.
     */
    private fun connect() {
        val url = model.url
        if (url == null) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return
        }

        if (context.settingsStore.requiresTokenAuth && !model.hasToken() && !model.hasOAuthSession()) {
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
                var oauthSession: CoderOAuthSessionContext? = null
                if (context.settingsStore.requiresTokenAuth && context.settingsStore.preferOAuth2IfAvailable && model.hasOAuthSession()) {
                    refreshOAuthToken()
                    oauthSession = model.oauthSession!!.copy()
                }

                val apiToken = if (context.settingsStore.requiresTokenAuth) model.token else null

                context.logger.info("Setting up the HTTP client...")
                val client = CoderRestClient(
                    context,
                    url,
                    apiToken,
                    oauthSession,
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
                    if (oauthSession != null) {
                        cli.login(oauthSession.tokenResponse!!.accessToken)
                    } else {
                        cli.login(apiToken!!)
                    }
                }
                logAndReportProgress("Successfully configured ${hostName}...")
                // allows interleaving with the back/cancel action
                yield()
                context.logger.info("Connection setup done, initializing the workspace poller...")
                onConnect.accept(client, cli)
                // Only invoke onTokenRefreshed when we actually have an OAuth session
                oauthSession?.let { session ->
                    onTokenRefreshed?.invoke(client.url, session)
                }
                model.clearFormData()
                model.goToDone()
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

        signInJob = context.cs.launch(CoroutineName("Async Http and CLI Setup"), block = connectionLogic)
    }

    private suspend fun refreshOAuthToken() {
        val session = model.oauthSession ?: return
        if (!session.tokenResponse?.accessToken.isNullOrBlank()) return

        logAndReportProgress("Refreshing OAuth token...")
        val tokenResponse = OAuth2Client(context).refreshToken(session)
        context.logger.info("Successfully refreshed access token")
        model.oauthSession = session.copy(tokenResponse = tokenResponse)
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
            model.clearFormData()
            if (jumpToMainPageOnError) {
                context.popupPluginMainPage()
            } else {
                model.goToFirst()
            }
        } else {
            if (context.settingsStore.requiresTokenAuth) {
                model.goToPrevious()
            } else {
                model.goToFirst()
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
