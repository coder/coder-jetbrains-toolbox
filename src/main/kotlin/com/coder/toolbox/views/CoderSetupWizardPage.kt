package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.Credentials
import com.coder.toolbox.views.state.PendingOAuthConnection
import com.coder.toolbox.views.state.WizardModel
import com.coder.toolbox.views.state.WizardStep
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URL

class CoderSetupWizardPage private constructor(
    private val context: CoderToolboxContext,
    private val settingsPage: CoderSettingsPage,
    visibilityState: StateFlow<ProviderVisibilityState>,
    private var autoLogin: Boolean = false,
    onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
    onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null
) : CoderPage(MutableStateFlow(context.i18n.ptrl("Setting up Coder")), false) {
    val model: WizardModel = WizardModel()
    private val settingsAction = Action(context, "Settings") {
        context.ui.showUiPage(settingsPage)
    }

    private val deploymentUrlStep = DeploymentUrlStep(context, model, visibilityState)
    private val tokenStep = TokenStep(context, model)
    private val connectStep = ConnectStep(
        context,
        model,
        visibilityState,
        navigateBack = this::navigateBackFromConnect,
        onConnect = onConnect,
        onTokenRefreshed = onTokenRefreshed
    )
    private val errorReporter = ErrorReporter.create(context, visibilityState, this.javaClass)
    private var stateCollectJob: Job? = null

    /**
     * Fields for this page, displayed in order.
     */
    override val fields: MutableStateFlow<List<UiField>> = MutableStateFlow(emptyList())
    override val actionButtons: MutableStateFlow<List<RunnableActionDescription>> = MutableStateFlow(emptyList())

    override fun beforeShow() {
        stateCollectJob?.cancel()
        stateCollectJob = context.cs.launch {
            model.step.collect { step ->
                context.logger.info("Wizard step changed to $step")
                displaySteps()
            }
        }
        errorReporter.flush()
    }

    private fun displaySteps() {
        when (model.currentStep()) {
            WizardStep.URL_REQUEST -> {
                fields.update {
                    listOf(deploymentUrlStep.panel)
                }
                actionButtons.update {
                    listOf(
                        Action(context, "Next", closesPage = false, actionBlock = {
                            if (deploymentUrlStep.onNext()) {
                                displaySteps()
                            }
                        }),
                        settingsAction
                    )
                }
                deploymentUrlStep.onVisible()
            }

            WizardStep.TOKEN_REQUEST -> {
                fields.update {
                    listOf(tokenStep.panel)
                }
                actionButtons.update {
                    listOf(
                        Action(context, "Connect", closesPage = false, actionBlock = {
                            if (tokenStep.onNext()) {
                                displaySteps()
                            }
                        }),
                        settingsAction,
                        Action(context, "Back", closesPage = false, actionBlock = {
                            tokenStep.onBack()
                            displaySteps()
                        })
                    )
                }
                tokenStep.onVisible()
            }

            WizardStep.CONNECT -> {
                fields.update {
                    listOf(connectStep.panel)
                }
                actionButtons.update {
                    listOf(
                        settingsAction,
                        Action(context, "Back", closesPage = false, actionBlock = {
                            connectStep.onBack()
                            displaySteps()
                        })
                    )
                }
                connectStep.onVisible()
            }
        }
    }

    fun pendingOAuthConnection(): PendingOAuthConnection? {
        val url = model.url ?: return null
        val oauthSession = model.oauthSession ?: return null
        return PendingOAuthConnection(url, oauthSession)
    }

    private fun navigateBackFromConnect() {
        if (autoLogin) {
            autoLogin = false
            model.clearFormData()
            model.goToFirst()
            return
        }
        if (context.settingsStore.requiresTokenAuth) {
            model.goToPrevious()
        } else {
            model.goToFirst()
        }
    }

    /**
     * Cancels any in-flight work owned by this wizard. Called by the page router
     * when the wizard is being replaced (e.g. by a deep link to a different
     * deployment) so its connect job doesn't keep running and clobber the new one.
     */
    fun dispose() {
        stateCollectJob?.cancel()
        connectStep.dispose()
    }

    override fun afterHide() {
        stateCollectJob?.cancel()
    }

    /**
     * Show an error as a popup on this page.
     */
    fun notify(message: String, ex: Throwable) = errorReporter.report(message, ex)


    companion object {
        fun deploymentUrlStep(
            context: CoderToolboxContext,
            settingsPage: CoderSettingsPage,
            visibilityState: StateFlow<ProviderVisibilityState>,
            onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
            onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null,
        ): CoderSetupWizardPage = CoderSetupWizardPage(
            context, settingsPage, visibilityState,
            onConnect = onConnect,
            onTokenRefreshed = onTokenRefreshed,
        ).apply { model.goToFirst() }

        fun connectStep(
            context: CoderToolboxContext,
            settingsPage: CoderSettingsPage,
            visibilityState: StateFlow<ProviderVisibilityState>,
            url: URL,
            credentials: Credentials,
            onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
            onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null,
        ): CoderSetupWizardPage = CoderSetupWizardPage(
            context, settingsPage, visibilityState,
            autoLogin = true,
            onConnect = onConnect,
            onTokenRefreshed = onTokenRefreshed,
        ).apply {
            model.url = url
            when (credentials) {
                is Credentials.MTls -> Unit
                is Credentials.Token -> model.token = credentials.value
                is Credentials.OAuth -> model.oauthSession = credentials.session
            }
            model.goTo(WizardStep.CONNECT)
        }
    }
}
