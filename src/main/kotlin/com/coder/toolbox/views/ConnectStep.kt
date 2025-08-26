package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.CoderCliSetupContext
import com.coder.toolbox.views.state.CoderCliSetupWizardState
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.CancellationException

private const val USER_HIT_THE_BACK_BUTTON = "User hit the back button"

/**
 * A page that connects a REST client and cli to Coder.
 */
class ConnectStep(
    private val context: CoderToolboxContext,
    private val shouldAutoLogin: StateFlow<Boolean>,
    private val jumpToMainPageOnError: Boolean,
    private val notify: (String, Throwable) -> Unit,
    private val refreshWizard: () -> Unit,
    private val onConnect: suspend (
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) -> Unit,
) : WizardStep {
    private var signInJob: Job? = null

    private val statusField = LabelField(context.i18n.pnotr(""))
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(statusField),
        RowGroup.RowField(errorField)
    )

    override fun onVisible() {
        errorField.textState.update {
            context.i18n.pnotr("")
        }

        if (context.settingsStore.requireTokenAuth && CoderCliSetupContext.isNotReadyForAuth()) {
            errorField.textState.update {
                context.i18n.pnotr("URL and token were not properly configured. Please go back and provide a proper URL and token!")
            }
            return
        }

        statusField.textState.update { context.i18n.pnotr("Connecting to ${CoderCliSetupContext.url!!.host}...") }
        connect()
    }

    /**
     * Try connecting to Coder with the provided URL and token.
     */
    private fun connect() {
        if (!CoderCliSetupContext.hasUrl()) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return
        }

        if (context.settingsStore.requireTokenAuth && !CoderCliSetupContext.hasToken()) {
            errorField.textState.update { context.i18n.ptrl("Token is required") }
            return
        }
        signInJob?.cancel()
        signInJob = context.cs.launch(CoroutineName("Http and CLI Setup")) {
            try {
                val client = CoderRestClient(
                    context,
                    CoderCliSetupContext.url!!,
                    if (context.settingsStore.requireTokenAuth) CoderCliSetupContext.token else null,
                    PluginManager.pluginInfo.version,
                )
                // allows interleaving with the back/cancel action
                yield()
                client.initializeSession()
                statusField.textState.update { (context.i18n.ptrl("Checking Coder CLI...")) }
                val cli = ensureCLI(
                    context, client.url,
                    client.buildVersion
                ) { progress ->
                    statusField.textState.update { (context.i18n.pnotr(progress)) }
                }
                // We only need to log in if we are using token-based auth.
                if (context.settingsStore.requireTokenAuth) {
                    statusField.textState.update { (context.i18n.ptrl("Configuring Coder CLI...")) }
                    // allows interleaving with the back/cancel action
                    yield()
                    cli.login(client.token!!)
                }
                statusField.textState.update { (context.i18n.ptrl("Successfully configured ${CoderCliSetupContext.url!!.host}...")) }
                // allows interleaving with the back/cancel action
                yield()
                CoderCliSetupContext.reset()
                CoderCliSetupWizardState.goToFirstStep()
                onConnect(client, cli)
            } catch (ex: CancellationException) {
                if (ex.message != USER_HIT_THE_BACK_BUTTON) {
                    notify("Connection to ${CoderCliSetupContext.url!!.host} was configured", ex)
                    onBack()
                    refreshWizard()
                }
            } catch (ex: Exception) {
                notify("Failed to configure ${CoderCliSetupContext.url!!.host}", ex)
                onBack()
                refreshWizard()
            }
        }
    }

    override fun onNext(): Boolean {
        return false
    }

    override fun onBack() {
        try {
            signInJob?.cancel(CancellationException(USER_HIT_THE_BACK_BUTTON))
        } finally {
            if (shouldAutoLogin.value) {
                CoderCliSetupContext.reset()
                if (jumpToMainPageOnError) {
                    context.popupPluginMainPage()
                } else {
                    CoderCliSetupWizardState.goToFirstStep()
                }
            } else {
                if (context.settingsStore.requireTokenAuth) {
                    CoderCliSetupWizardState.goToPreviousStep()
                } else {
                    CoderCliSetupWizardState.goToFirstStep()
                }
            }
        }
    }
}
