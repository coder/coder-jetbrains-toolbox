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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.URL

class CoderCliSetupWizardPage(
    private val context: CoderToolboxContext,
    private val settingsPage: CoderSettingsPage,
    visibilityState: StateFlow<ProviderVisibilityState>,
    initialAutoSetup: Boolean = false,
    jumpToMainPageOnError: Boolean = false,
    connectSynchronously: Boolean = false,
    onConnect: suspend (
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) -> Unit,
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
        connectSynchronously = connectSynchronously,
        visibilityState,
        refreshWizard = this::displaySteps,
        onConnect = onConnect,
        onTokenRefreshed = onTokenRefreshed
    )
    private val errorReporter = ErrorReporter.create(context, visibilityState, this.javaClass)

    /**
     * Fields for this page, displayed in order.
     */
    override val fields: MutableStateFlow<List<UiField>> = MutableStateFlow(emptyList())
    override val actionButtons: MutableStateFlow<List<RunnableActionDescription>> = MutableStateFlow(emptyList())


    override fun beforeShow() {
        displaySteps()
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
        }
    }

    /**
     * Show an error as a popup on this page.
     */
    fun notify(message: String, ex: Throwable) = errorReporter.report(message, ex)
}
