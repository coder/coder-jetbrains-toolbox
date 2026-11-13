package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.ConnectionOrigin
import com.coder.toolbox.views.state.Credentials
import com.coder.toolbox.views.state.PendingOAuthConnection
import com.coder.toolbox.views.state.WizardModel
import com.coder.toolbox.views.state.WizardStep
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URL

class CoderSetupWizardPage private constructor(
    private val context: CoderToolboxContext,
    private val settingsPage: CoderSettingsPage,
    onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
    onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null
) : CoderPage(MutableStateFlow(context.i18n.ptrl("Setting up Coder")), false) {
    val model: WizardModel = WizardModel()
    private val settingsAction = Action(context, "Settings") {
        context.ui.showUiPage(settingsPage)
    }

    private val deploymentUrlStep = DeploymentUrlStep(context, model)
    private val tokenStep = TokenStep(context, model)
    private val connectStep = ConnectStep(
        context,
        model,
        navigateBack = this::navigateBackFromConnect,
        onConnect = onConnect,
        onTokenRefreshed = onTokenRefreshed
    )
    private var stateCollectJob: Job? = null
    @Volatile
    private var disposed = false

    /**
     * Fields for this page, displayed in order.
     */
    override val fields: MutableStateFlow<List<UiField>> = MutableStateFlow(emptyList())
    override val actionButtons: MutableStateFlow<List<RunnableActionDescription>> = MutableStateFlow(emptyList())

    fun pageForDisplay(): CoderSetupWizardPage? = when (val step = model.currentStep()) {
        is WizardStep.Connecting -> if (step.origin == ConnectionOrigin.SAVED_SESSION) null else this
        WizardStep.SignedIn -> null
        else -> this
    }

    internal fun startSavedSessionRestore() {
        if (model.currentStep() == WizardStep.Connecting(ConnectionOrigin.SAVED_SESSION)) connectStep.startConnection()
    }

    override fun beforeShow() {
        prepareStepObserver()?.start()
    }

    @Synchronized
    private fun prepareStepObserver(): Job? {
        if (disposed || stateCollectJob?.isCompleted == false) return null
        val job = context.cs.launch(start = CoroutineStart.LAZY) {
            model.step.collect { step ->
                context.logger.info("Wizard step changed to $step")
                displayStep(step)
            }
        }
        stateCollectJob = job
        return job
    }

    private fun displayStep(step: WizardStep) {
        when (step) {
            WizardStep.UrlRequest -> {
                fields.update {
                    listOf(deploymentUrlStep.panel)
                }
                actionButtons.update {
                    listOf(
                        Action(context, "Next", closesPage = false) { deploymentUrlStep.onNext() },
                        settingsAction
                    )
                }
                deploymentUrlStep.onVisible()
            }

            WizardStep.TokenRequest -> {
                fields.update {
                    listOf(tokenStep.panel)
                }
                actionButtons.update {
                    listOf(
                        Action(context, "Connect", closesPage = false) { tokenStep.onNext() },
                        settingsAction,
                        Action(context, "Back", closesPage = false) { tokenStep.onBack() }
                    )
                }
                tokenStep.onVisible()
            }

            is WizardStep.Connecting -> {
                fields.update {
                    listOf(connectStep.panel)
                }
                actionButtons.update {
                    listOf(
                        settingsAction,
                        Action(context, "Back", closesPage = false) { connectStep.onBack() }
                    )
                }
                connectStep.onVisible()
            }

            // Toolbox can bring this wizard back after Settings or during startup.
            // Ask for the workspace list when the completed wizard is actually shown.
            WizardStep.SignedIn -> context.envPageManager.showPluginEnvironmentsPage(false)
        }
    }

    fun pendingOAuthConnection(): PendingOAuthConnection? {
        val url = model.url ?: return null
        val oauthSession = model.oauthSession ?: return null
        return PendingOAuthConnection(url, oauthSession)
    }

    private fun navigateBackFromConnect() {
        val phase = model.currentStep() as? WizardStep.Connecting ?: return
        if (phase.origin == ConnectionOrigin.FORM) {
            model.goTo(if (context.settingsStore.requiresTokenAuth) WizardStep.TokenRequest else WizardStep.UrlRequest)
            return
        }
        val revealForm = phase.origin == ConnectionOrigin.SAVED_SESSION
        model.clearFormData()
        model.goToFirst()
        if (revealForm) {
            context.cs.launch {
                if (!disposed) context.ui.showUiPageSuspending(this@CoderSetupWizardPage)
            }
        }
    }

    /**
     * Cancels any in-flight work owned by this wizard. Called by the page router
     * when the wizard is being replaced (e.g. by a deep link to a different
     * deployment) so its connect job doesn't keep running and clobber the new one.
     */
    @Synchronized
    fun dispose() {
        disposed = true
        afterHide()
        connectStep.dispose()
    }

    @Synchronized
    override fun afterHide() {
        stateCollectJob?.cancel()
        stateCollectJob = null
    }

    companion object {
        fun deploymentUrlStep(
            context: CoderToolboxContext,
            settingsPage: CoderSettingsPage,
            onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
            onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null,
        ): CoderSetupWizardPage = CoderSetupWizardPage(
            context, settingsPage,
            onConnect = onConnect,
            onTokenRefreshed = onTokenRefreshed,
        ).apply { model.goToFirst() }

        fun connectStep(
            context: CoderToolboxContext,
            settingsPage: CoderSettingsPage,
            url: URL,
            credentials: Credentials,
            onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
            onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null,
            origin: ConnectionOrigin = ConnectionOrigin.SUPPLIED_CREDENTIALS,
        ): CoderSetupWizardPage = CoderSetupWizardPage(
            context, settingsPage,
            onConnect = onConnect,
            onTokenRefreshed = onTokenRefreshed,
        ).apply {
            model.url = url
            when (credentials) {
                is Credentials.MTls -> Unit
                is Credentials.Token -> model.token = credentials.value
                is Credentials.OAuth -> model.oauthSession = credentials.session
            }
            model.goTo(WizardStep.Connecting(origin))
        }
    }
}
