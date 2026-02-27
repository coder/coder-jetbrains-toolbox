package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.CoderSetupWizardState
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

class CoderCliSetupWizardPage(
    private val context: CoderToolboxContext,
    private val settingsPage: CoderSettingsPage,
    visibilityState: StateFlow<ProviderVisibilityState>,
    initialAutoSetup: Boolean = false,
    jumpToMainPageOnError: Boolean = false,
    onConnect: SuspendBiConsumer<CoderRestClient, CoderCLIManager>,
    onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null
) : CoderPage(MutableStateFlow(context.i18n.ptrl("Setting up Coder")), false) {
    private val shouldAutoSetup = MutableStateFlow(initialAutoSetup)
    private val settingsAction = Action(context, "Settings") {
        context.ui.showUiPage(settingsPage)
    }

    private val deploymentUrlStep = DeploymentUrlStep(context, visibilityState)
    private val tokenStep = TokenStep(context)
    private val connectStep = ConnectStep(
        context,
        shouldAutoLogin = shouldAutoSetup,
        jumpToMainPageOnError = jumpToMainPageOnError,
        visibilityState,
        refreshWizard = this::displaySteps,
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
            CoderSetupWizardState.step.collect { step ->
                context.logger.info("Wizard step changed to $step")
                displaySteps()
            }
        }
        errorReporter.flush()
    }

    private fun displaySteps() {
        when (CoderSetupWizardState.currentStep()) {
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
                            shouldAutoSetup.update {
                                false
                            }
                            displaySteps()
                        })
                    )
                }
                connectStep.onVisible()
            }

            WizardStep.DONE -> {
                context.logger.info("Closing the Setup Wizard")
                stateCollectJob?.cancel()
                context.ui.hideUiPage(this)
                CoderSetupWizardState.goToFirstStep()
            }
        }
    }

    override fun afterHide() {
        stateCollectJob?.cancel()
    }

    /**
     * Show an error as a popup on this page.
     */
    fun notify(message: String, ex: Throwable) = errorReporter.report(message, ex)
}
