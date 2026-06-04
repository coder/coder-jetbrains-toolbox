package com.coder.toolbox

import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.ConnectionMonitoringService
import com.coder.toolbox.util.toURL
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.os.LocalDesktopManager
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import com.jetbrains.toolbox.api.remoteDev.connection.ToolboxProxySettings
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URL
import java.util.UUID

@Suppress("UnstableApiUsage")
data class CoderToolboxContext(
    val ui: ToolboxUi,
    val envPageManager: EnvironmentUiPageManager,
    val envStateColorPalette: EnvironmentStateColorPalette,
    val remoteIdeOrchestrator: RemoteToolsHelper,
    val jbClientOrchestrator: ClientHelper,
    val desktop: LocalDesktopManager,
    val cs: CoroutineScope,
    val logger: Logger,
    val i18n: LocalizableStringFactory,
    val settingsStore: CoderSettingsStore,
    val secrets: CoderSecretsStore,
    val proxySettings: ToolboxProxySettings,
    val connectionMonitoringService: ConnectionMonitoringService,
) {
    /**
     * Try to find a URL.
     *
     * In order of preference:
     *
     * 1. Last used URL from the settings.
     * 2. Last used URL from the secrets store.
     * 3. Default URL
     */
    val deploymentUrl: URL
        get() {
            return settingsStore.lastDeploymentURL?.takeIf { it.isNotBlank() }?.toURL()
                ?: secrets.lastDeploymentURL.takeIf { it.isNotBlank() }?.toURL()
                ?: settingsStore.defaultURL.toURL()
        }

    fun logAndShowError(title: String, error: String) {
        logger.error(error)
        showSnackbar(title, error)
    }

    fun logAndShowError(title: String, error: String, exception: Exception) {
        logger.error(exception, error)
        showSnackbar(title, error)
    }

    fun logAndShowWarning(title: String, warning: String) {
        logger.warn(warning)
        showSnackbar(title, warning)
    }

    fun logAndShowInfo(title: String, info: String) {
        logger.info(info)
        showSnackbar(title, info)
    }

    /**
     * Displays a snackbar on a child of the plugin coroutine scope rather than on the
     * caller's coroutine, without waiting for it.
     *
     * Toolbox keeps the [ToolboxUi.showSnackbar] coroutine suspended for the entire lifetime
     * of the snackbar and cancels it (rather than resuming it) when the snackbar goes away.
     * Calling it directly on the caller's coroutine would therefore either block the caller
     * until the snackbar is gone or, on dismissal, abruptly cancel the caller (e.g. the URI
     * handler) - skipping any code that runs after the error is shown, such as resetting the
     * busy state. Launching it fire-and-forget on the plugin scope lets the caller continue
     * immediately while the snackbar lives independently.
     */
    fun showSnackbar(title: String, text: String) {
        cs.launch(CoroutineName("snackbar")) {
            try {
                ui.showSnackbar(
                    UUID.randomUUID().toString(),
                    i18n.pnotr(title),
                    i18n.pnotr(text),
                    i18n.ptrl("OK")
                )
            } catch (_: CancellationException) {
                // Expected when the snackbar is dismissed or the plugin scope shuts down.
            } catch (ex: Exception) {
                logger.error(ex, "Failed to display snackbar with title '$title'")
            }
        }
    }

    fun popupPluginMainPage() {
        this.ui.showWindow()
        this.envPageManager.showPluginEnvironmentsPage(true)
    }
}
