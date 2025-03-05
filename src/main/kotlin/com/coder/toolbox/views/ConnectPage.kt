package com.coder.toolbox.views

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.settings.CoderSettings
import com.coder.toolbox.util.humanizeConnectionError
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.CoroutineScope
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
    private val serviceLocator: ServiceLocator,
    private val url: URL,
    private val token: String?,
    private val settings: CoderSettings,
    private val httpClient: OkHttpClient,
    title: LocalizableString,
    private val onCancel: () -> Unit,
    private val onConnect: (
        client: CoderRestClient,
        cli: CoderCLIManager,
    ) -> Unit,
) : CoderPage(serviceLocator, title) {
    private val coroutineScope = serviceLocator.getService(CoroutineScope::class.java)
    private val i18n = serviceLocator.getService(LocalizableStringFactory::class.java)

    private var signInJob: Job? = null

    private var statusField = LabelField(i18n.pnotr("Connecting to ${url.host}..."))

    override val description: LocalizableString = i18n.pnotr("Please wait while we configure Toolbox for ${url.host}.")

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
        if (errorField != null) Action(i18n.ptrl("Retry"), closesPage = false) { retry() } else null,
        if (errorField != null) Action(i18n.ptrl("Cancel"), closesPage = false) { onCancel() } else null,
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
        updateStatus(i18n.pnotr("Connecting to ${url.host}..."), null)
        connect()
    }

    /**
     * Try connecting to Coder with the provided URL and token.
     */
    private fun connect() {
        signInJob?.cancel()
        signInJob = coroutineScope.launch {
            try {
                // The http client Toolbox gives us is already set up with the
                // proxy config, so we do net need to explicitly add it.
                val client = CoderRestClient(
                    url,
                    token,
                    settings,
                    proxyValues = null,
                    PluginManager.pluginInfo.version,
                    httpClient
                )
                client.authenticate()
                updateStatus(i18n.ptrl("Checking Coder binary..."), error = null)
                val cli = ensureCLI(client.url, client.buildVersion, settings) { status ->
                    updateStatus(i18n.pnotr(status), error = null)
                }
                // We only need to log in if we are using token-based auth.
                if (client.token != null) {
                    updateStatus(i18n.ptrl("Configuring CLI..."), error = null)
                    cli.login(client.token)
                }
                onConnect(client, cli)

            } catch (ex: Exception) {
                val msg = humanizeConnectionError(url, settings.requireTokenAuth, ex)
                notify("Failed to configure ${url.host}", ex)
                updateStatus(i18n.pnotr("Failed to configure ${url.host}"), msg)
            }
        }
    }
}
