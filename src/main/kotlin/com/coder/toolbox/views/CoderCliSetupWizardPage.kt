package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.CoderCliSetupWizardState
import com.coder.toolbox.views.state.WizardStep
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class CoderCliSetupWizardPage(
    private val context: CoderToolboxContext,
    private val settingsPage: CoderSettingsPage,
    visibilityState: StateFlow<ProviderVisibilityState>,
    initialAutoSetup: Boolean = false,
    jumpToMainPageOnError: Boolean = false,
    onConnect: suspend (
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) -> Unit,
) : CoderPage(MutableStateFlow(context.i18n.ptrl("Setting up Coder")), false) {
    private val shouldAutoSetup = MutableStateFlow(initialAutoSetup)
    private val settingsAction = Action(context.i18n.ptrl("Settings"), actionBlock = {
        context.ui.showUiPage(settingsPage)
    })

    private val deploymentUrlStep = DeploymentUrlStep(context, visibilityState)
    private val tokenStep = TokenStep(context)
    private val connectStep = ConnectStep(
        context,
        shouldAutoLogin = shouldAutoSetup,
        jumpToMainPageOnError,
        visibilityState,
        this::displaySteps,
        onConnect
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
        when (CoderCliSetupWizardState.currentStep()) {
            WizardStep.URL_REQUEST -> {
                fields.update {
                    listOf(deploymentUrlStep.panel)
                }
                actionButtons.update {
                    listOf(
                        Action(context.i18n.ptrl("Next"), closesPage = false, actionBlock = {
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
                        Action(context.i18n.ptrl("Connect"), closesPage = false, actionBlock = {
                            if (tokenStep.onNext()) {
                                displaySteps()
                            }
                        }),
                        settingsAction,
                        Action(context.i18n.ptrl("Back"), closesPage = false, actionBlock = {
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
                        Action(context.i18n.ptrl("Back"), closesPage = false, actionBlock = {
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
