package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.views.state.AuthContext
import com.coder.toolbox.views.state.AuthWizardState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
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
    private val authContext: AuthContext,
    private val shouldAutoLogin: StateFlow<Boolean>,
    private val notify: (String, Throwable) -> Unit,
    private val refreshWizard: () -> Unit,
    private val onConnect: (
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

    override val nextButtonTitle: LocalizableString? = null

    override fun onVisible() {
        errorField.textState.update {
            context.i18n.pnotr("")
        }
        if (authContext.isNotReadyForAuth()) return

        statusField.textState.update { context.i18n.pnotr("Connecting to ${authContext.url!!.host}...") }
        connect()
    }

    /**
     * Try connecting to Coder with the provided URL and token.
     */
    private fun connect() {
        val url = authContext.url
        val token = authContext.token
        if (url == null) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return
        }

        if (token.isNullOrBlank()) {
            errorField.textState.update { context.i18n.ptrl("Token is required") }
            return
        }
        signInJob?.cancel()
        signInJob = context.cs.launch {
            try {
                statusField.textState.update { (context.i18n.ptrl("Authenticating to ${url.host}...")) }
                val client = CoderRestClient(
                    context,
                    url,
                    token,
                    PluginManager.pluginInfo.version,
                )
                // allows interleaving with the back/cancel action
                yield()
                client.authenticate()
                statusField.textState.update { (context.i18n.ptrl("Checking Coder binary...")) }
                val cli = ensureCLI(context, client.url, client.buildVersion)
                // We only need to log in if we are using token-based auth.
                if (client.token != null) {
                    statusField.textState.update { (context.i18n.ptrl("Configuring CLI...")) }
                    // allows interleaving with the back/cancel action
                    yield()
                    cli.login(client.token)
                }
                statusField.textState.update { (context.i18n.ptrl("Successfully configured ${url.host}...")) }
                // allows interleaving with the back/cancel action
                yield()
                onConnect(client, cli)

                authContext.reset()
                AuthWizardState.resetSteps()
            } catch (ex: CancellationException) {
                if (ex.message != USER_HIT_THE_BACK_BUTTON) {
                    notify("Connection to ${url.host} was configured", ex)
                    onBack()
                    refreshWizard()
                }
            } catch (ex: Exception) {
                notify("Failed to configure ${url.host}", ex)
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
                AuthWizardState.resetSteps()
                context.secrets.rememberMe = false
            } else {
                AuthWizardState.goToPreviousStep()
            }
        }
    }
}
