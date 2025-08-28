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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

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
        // Capture the host name early for error reporting
        val hostName = CoderCliSetupContext.url!!.host

        signInJob?.cancel()
        signInJob = context.cs.launch(CoroutineName("Http and CLI Setup")) {
            try {
                context.logger.info("Setting up the HTTP client...")
                val client = CoderRestClient(
                    context,
                    CoderCliSetupContext.url!!,
                    if (context.settingsStore.requireTokenAuth) CoderCliSetupContext.token else null,
                    PluginManager.pluginInfo.version,
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
                if (context.settingsStore.requireTokenAuth) {
                    logAndReportProgress("Configuring Coder CLI...")
                    // allows interleaving with the back/cancel action
                    yield()
                    cli.login(client.token!!)
                }
                logAndReportProgress("Successfully configured ${hostName}...")
                // allows interleaving with the back/cancel action
                yield()
                context.logger.info("Connection setup done, initializing the workspace poller...")
                onConnect(client, cli)

                CoderCliSetupContext.reset()
                CoderCliSetupWizardState.goToFirstStep()
                context.envPageManager.showPluginEnvironmentsPage()
            } catch (ex: CancellationException) {
                if (ex.message != USER_HIT_THE_BACK_BUTTON) {
                    notify("Connection to $hostName was configured", ex)
                    handleNavigation()
                    refreshWizard()
                }
            } catch (ex: Exception) {
                notify("Failed to configure $hostName", ex)
                handleNavigation()
                refreshWizard()
            }
        }
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

    override fun onNext(): Boolean {
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
