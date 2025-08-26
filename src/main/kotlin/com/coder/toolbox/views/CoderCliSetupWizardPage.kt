package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.views.state.CoderCliSetupWizardState
import com.coder.toolbox.views.state.WizardStep
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class CoderCliSetupWizardPage(
    private val context: CoderToolboxContext,
    private val settingsPage: CoderSettingsPage,
    private val visibilityState: MutableStateFlow<ProviderVisibilityState>,
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

    private val deploymentUrlStep = DeploymentUrlStep(context, this::notify)
    private val tokenStep = TokenStep(context)
    private val connectStep = ConnectStep(
        context,
        shouldAutoLogin = shouldAutoSetup,
        jumpToMainPageOnError,
        this::notify,
        this::displaySteps,
        onConnect
    )

    /**
     * Fields for this page, displayed in order.
     */
    override val fields: MutableStateFlow<List<UiField>> = MutableStateFlow(emptyList())
    override val actionButtons: MutableStateFlow<List<RunnableActionDescription>> = MutableStateFlow(emptyList())

    private val errorBuffer = mutableListOf<Throwable>()

    override fun beforeShow() {
        displaySteps()
        if (errorBuffer.isNotEmpty() && visibilityState.value.applicationVisible) {
            errorBuffer.forEach {
                showError(it)
            }
            errorBuffer.clear()
        }
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
    fun notify(logPrefix: String, ex: Throwable) {
        context.logger.error(ex, logPrefix)
        if (!visibilityState.value.applicationVisible) {
            context.logger.debug("Toolbox is not yet visible, scheduling error to be displayed later")
            errorBuffer.add(ex)
            return
        }
        showError(ex)
    }

    private fun showError(ex: Throwable) {
        val textError = if (ex is APIResponseException) {
            if (!ex.reason.isNullOrBlank()) {
                ex.reason
            } else ex.message
        } else ex.message

        context.cs.launch(CoroutineName("Coder Setup Visual Error Reporting")) {
            context.ui.showSnackbar(
                UUID.randomUUID().toString(),
                context.i18n.ptrl("Error encountered while setting up Coder"),
                context.i18n.pnotr(textError ?: ""),
                context.i18n.ptrl("Dismiss")
            )
        }
    }
}
