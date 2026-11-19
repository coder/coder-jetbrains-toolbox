package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.oauth.OAuth2Client
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.util.origin
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.ConnectionOrigin
import com.coder.toolbox.views.state.WizardModel
import com.coder.toolbox.views.state.WizardStep.Connecting
import com.coder.toolbox.views.state.WizardStep.SignedIn
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.net.URL

private const val USER_HIT_THE_BACK_BUTTON = "User hit the back button"
private const val WIZARD_WAS_DISPOSED = "Wizard was disposed"

/**
 * A page that connects a REST client and cli to Coder.
 */
class ConnectStep(
    private val context: CoderToolboxContext,
    private val model: WizardModel,
    private val navigateBack: () -> Unit,
    private val onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
    private val onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null
) : WizardStep {
    private var signInJob: Job? = null
    private var disposed = false
    private val connectionMutex = Mutex()

    private val statusField = LabelField(context.i18n.pnotr(""))
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(statusField),
        RowGroup.RowField(errorField)
    )

    override fun onVisible() = startConnection()

    fun startConnection() {
        prepareConnection()?.start()
    }

    @Synchronized
    private fun prepareConnection(): Job? {
        if (disposed || signInJob != null || model.currentStep() !is Connecting) return null

        errorField.textState.update { context.i18n.pnotr("") }
        if (context.settingsStore.requiresTokenAuth && model.isNotReadyForAuth()) {
            errorField.textState.update {
                context.i18n.pnotr("URL and token were not properly configured. Please go back and provide a proper URL and token!")
            }
            return null
        }
        val url = model.url
        if (url == null) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return null
        }

        statusField.textState.update { context.i18n.pnotr("Connecting to ${url.origin() ?: url.host}...") }
        // Record the job before dispatching it. Toolbox can show the same step again during startup.
        val job = context.cs.launch(CoroutineName("Async Http and CLI Setup"), start = CoroutineStart.LAZY) {
            // A retry waits for a cancelled CLI process to finish cleaning up.
            connectionMutex.withLock { connect(url) }
        }
        signInJob = job
        return job
    }

    private suspend fun connect(url: URL) {
        var pendingClient: CoderRestClient? = null
        try {
            val requiresTokenAuth = context.settingsStore.requiresTokenAuth
            val oauthSession = if (requiresTokenAuth && context.settingsStore.preferOAuth2IfAvailable) {
                oauthSessionForConnection()
            } else null
            val apiToken = if (requiresTokenAuth) model.token else null

            context.logger.info("Setting up the HTTP client...")
            val client = CoderRestClient(
                context,
                url,
                apiToken,
                oauthSession,
                PluginManager.pluginInfo.version,
                onTokenRefreshed
            )
            pendingClient = client
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
            if (context.settingsStore.requiresTokenAuth) {
                logAndReportProgress("Configuring Coder CLI...")
                // allows interleaving with the back/cancel action
                yield()
                cli.login(client.requireSessionToken())
            }
            logAndReportProgress("Successfully configured ${url.host}...")
            // allows interleaving with the back/cancel action
            yield()
            context.logger.info("Connection setup done, initializing the workspace poller...")
            onConnect.accept(client, cli)
            pendingClient = null // The provider now owns this client.
            // The CLI already uses this token. Save the OAuth session for the next sign-in.
            oauthSession?.let { session ->
                context.secrets.storeOAuthFor(client.url.toString(), session)
            }
        } catch (ex: Exception) {
            reportConnectionFailure(currentCoroutineContext().job, url, ex)
        } finally {
            pendingClient?.close()
        }
    }

    private suspend fun oauthSessionForConnection(): CoderOAuthSessionContext? {
        val session = model.oauthSession ?: return null
        val response = session.tokenResponse ?: return null
        if (response.accessToken.isNotBlank()) return session

        logAndReportProgress("Refreshing OAuth token...")
        val refreshed = session.copy(tokenResponse = OAuth2Client(context).refreshToken(session))
        context.logger.info("Successfully refreshed access token")
        model.oauthSession = refreshed
        return refreshed
    }

    private fun logAndReportProgress(msg: String) {
        context.logger.info(msg)
        statusField.textState.update { context.i18n.pnotr(msg) }
    }

    @Synchronized
    private fun reportConnectionFailure(job: Job, url: URL, ex: Exception) {
        job.ensureActive()
        if (signInJob !== job) return
        if (model.currentStep() == SignedIn) {
            context.logger.warn(ex, "Failed to update session credentials after sign-in")
            return
        }
        signInJob = null
        context.logger.logAndShowError(
            "Error encountered while setting up Coder",
            "Failed to configure ${url.origin() ?: url.host}. ${ex.message}",
            ex,
        )
        navigateBack()
    }

    override suspend fun onNext(): Boolean {
        return false
    }

    @Synchronized
    override fun onBack() {
        val phase = model.currentStep() as? Connecting ?: return
        if (disposed || phase.origin == ConnectionOrigin.SAVED_SESSION) return
        try {
            context.logger.info("Back button was pressed, cancelling in-progress connection setup...")
            signInJob?.cancel(CancellationException(USER_HIT_THE_BACK_BUTTON))
        } finally {
            signInJob = null
            navigateBack()
        }
    }

    /**
     * Cancels any in-flight connection without navigating. Used when the wizard
     * itself is being torn down by an external trigger (e.g. a deep link to a
     * different deployment).
     */
    @Synchronized
    fun dispose() {
        disposed = true
        signInJob?.cancel(CancellationException(WIZARD_WAS_DISPOSED))
        signInJob = null
    }
}
