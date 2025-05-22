package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.AuthContext
import com.coder.toolbox.views.state.AuthWizardState
import com.coder.toolbox.views.state.WizardStep
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class AuthWizardPage(
    private val context: CoderToolboxContext,
    private val settingsPage: CoderSettingsPage,
    initialAutoLogin: Boolean = false,
    onConnect: (
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) -> Unit,
) : CoderPage(context, context.i18n.ptrl("Authenticate to Coder"), false) {
    private val shouldAutoLogin = MutableStateFlow(initialAutoLogin)
    private val settingsAction = Action(context.i18n.ptrl("Settings"), actionBlock = {
        context.ui.showUiPage(settingsPage)
    })

    private val authContext: AuthContext = AuthContext()
    private val signInStep = SignInStep(context, authContext, this::notify)
    private val tokenStep = TokenStep(context, authContext)
    private val connectStep = ConnectStep(
        context,
        authContext,
        shouldAutoLogin,
        this::notify,
        this::displaySteps, onConnect
    )


    /**
     * Fields for this page, displayed in order.
     */
    override val fields: MutableStateFlow<List<UiField>> = MutableStateFlow(emptyList())
    override val actionButtons: MutableStateFlow<List<RunnableActionDescription>> = MutableStateFlow(emptyList())

    override fun beforeShow() {
        displaySteps()
    }

    private fun displaySteps() {
        when (AuthWizardState.currentStep()) {
            WizardStep.URL_REQUEST -> {
                fields.update {
                    listOf(signInStep.panel)
                }
                actionButtons.update {
                    listOf(
                        Action(context.i18n.ptrl("Sign In"), closesPage = false, actionBlock = {
                            if (signInStep.onNext()) {
                                displaySteps()
                            }
                        }),
                        settingsAction
                    )
                }
                signInStep.onVisible()
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

            WizardStep.LOGIN -> {
                fields.update {
                    listOf(connectStep.panel)
                }
                actionButtons.update {
                    listOf(
                        settingsAction,
                        Action(context.i18n.ptrl("Back"), closesPage = false, actionBlock = {
                            connectStep.onBack()
                            shouldAutoLogin.update {
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
}
