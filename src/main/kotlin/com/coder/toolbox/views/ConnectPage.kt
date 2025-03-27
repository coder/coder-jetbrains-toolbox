package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.util.humanizeConnectionError
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.URL

/**
 * A page that connects a REST client and cli to Coder.
 */
class ConnectPage(
    private val context: CoderToolboxContext,
    private val url: URL,
    private val token: String?,
    private val httpClient: OkHttpClient,
    private val onCancel: () -> Unit,
    private val onConnect: (
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) -> Unit,
) : CoderPage(context, context.i18n.ptrl("Connecting to Coder")) {
    private val settings = context.settingsStore.readOnly()
    private var signInJob: Job? = null

    private var statusField = LabelField(context.i18n.pnotr("Connecting to ${url.host}..."))

    override val description: LocalizableString =
        context.i18n.pnotr("Please wait while we configure Toolbox for ${url.host}.")

    init {
        connect()
    }

    /**
     * Fields for this page, displayed in order.
     *
     * TODO@JB: This looks kinda sparse.  A centered spinner would be welcome.
     */
    override val fields: StateFlow<List<UiField>> = MutableStateFlow(
        listOfNotNull(
            statusField,
            errorField
        )
    )

    /**
     * Show a retry button on error.
     */
    override val actionButtons: StateFlow<List<RunnableActionDescription>> = MutableStateFlow(
        listOfNotNull(
            if (errorField != null) Action(context.i18n.ptrl("Retry"), closesPage = false) { retry() } else null,
            if (errorField != null) Action(context.i18n.ptrl("Cancel"), closesPage = false) { onCancel() } else null,
        ))

    /**
     * Update the status and error fields then refresh.
     */
    private fun updateStatus(newStatus: LocalizableString, error: String?) {
        statusField = LabelField(newStatus)
        updateError(error) // Will refresh.
    }

    /**
     * Try connecting again after an error.
     */
    private fun retry() {
        updateStatus(context.i18n.pnotr("Connecting to ${url.host}..."), null)
        connect()
    }

    /**
     * Try connecting to Coder with the provided URL and token.
     */
    private fun connect() {
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
                    httpClient
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

            } catch (ex: Exception) {
                val msg = humanizeConnectionError(url, settings.requireTokenAuth, ex)
                notify("Failed to configure ${url.host}", ex)
                updateStatus(context.i18n.pnotr("Failed to configure ${url.host}"), msg)
            }
        }
    }
}
