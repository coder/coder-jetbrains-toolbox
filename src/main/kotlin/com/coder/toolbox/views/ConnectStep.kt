package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.util.humanizeConnectionError
import com.coder.toolbox.util.toURL
import com.coder.toolbox.views.state.AuthWizardState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A page that connects a REST client and cli to Coder.
 */
class ConnectStep(
    private val context: CoderToolboxContext,
    private val notify: (String, Throwable) -> Unit,
    private val onConnect: (
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) -> Unit,
) : WizardStep {
    private val settings = context.settingsStore.readOnly()
    private var signInJob: Job? = null

    private val statusField = LabelField(context.i18n.pnotr(""))

    //    override val description: LocalizableString = context.i18n.pnotr("Please wait while we configure Toolbox for ${url.host}.")
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(statusField),
        RowGroup.RowField(errorField)
    )

    override val nextButtonTitle: LocalizableString? = null

    override fun onVisible() {
        val url = context.deploymentUrl?.first?.toURL()
        statusField.textState.update { context.i18n.pnotr("Connecting to ${url?.host}...") }
        connect()
    }

    /**
     * Try connecting to Coder with the provided URL and token.
     */
    private fun connect() {
        val url = context.deploymentUrl?.first?.toURL()
        val token = context.getToken(context.deploymentUrl?.first)?.first
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
                // The http client Toolbox gives us is already set up with the
                // proxy config, so we do net need to explicitly add it.
                val client = CoderRestClient(
                    context,
                    url,
                    token,
                    proxyValues = null,
                    PluginManager.pluginInfo.version,
                )
                client.authenticate()
                updateStatus(context.i18n.ptrl("Checking Coder binary..."), error = null)
                val cli = ensureCLI(context, client.url, client.buildVersion)
                // We only need to log in if we are using token-based auth.
                if (client.token != null) {
                    updateStatus(context.i18n.ptrl("Configuring CLI..."), error = null)
                    cli.login(client.token)
                }
                onConnect(client, cli)
                AuthWizardState.resetSteps()

            } catch (ex: Exception) {
                val msg = humanizeConnectionError(url, settings.requireTokenAuth, ex)
                notify("Failed to configure ${url.host}", ex)
                updateStatus(context.i18n.pnotr("Failed to configure ${url.host}"), msg)
            }
        }
    }

    override fun onNext(): Boolean {
        return false
    }

    override fun onBack() {
        AuthWizardState.goToPreviousStep()
    }

    /**
     * Update the status and error fields then refresh.
     */
    private fun updateStatus(newStatus: LocalizableString, error: String?) {
        statusField.textState.update { newStatus }
        if (!error.isNullOrBlank()) {
            errorField.textState.update { context.i18n.pnotr(error) }
        }
    }
//
//    /**
//     * Try connecting again after an error.
//     */
//    private fun retry() {
//        updateStatus(context.i18n.pnotr("Connecting to ${url.host}..."), null)
//        connect()
//    }
}
