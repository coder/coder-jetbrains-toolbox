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
import com.jetbrains.toolbox.api.ui.components.UiComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URL

@Suppress("UnstableApiUsage")
data class CoderToolboxContext(
    val ui: ToolboxUi,
    val uiComponents: UiComponents,
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
) {
    val connectionMonitoringService: ConnectionMonitoringService = ConnectionMonitoringService(this)

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
        showInfoPopup(title, error)
    }

    fun logAndShowError(title: String, error: String, exception: Exception) {
        logger.error(exception, error)
        showInfoPopup(title, error)
    }

    fun logAndShowWarning(title: String, warning: String) {
        logger.warn(warning)
        showInfoPopup(title, warning)
    }

    fun logAndShowInfo(title: String, info: String) {
        logger.info(info)
        showInfoPopup(title, info)
    }

    /**
     * Displays an informational popup on a child of the plugin coroutine scope rather than on
     * the caller's coroutine, without waiting for it.
     *
     * Unlike [ToolboxUi.showSnackbar], a popup is backed by a persistent dialog state: it is
     * still rendered once the window becomes visible even if it was requested while the window
     * was hidden, it is not silently dropped when several are requested, and dismissing it
     * resumes the [ToolboxUi.showInfoPopup] coroutine normally instead of cancelling it.
     *
     * It is launched fire-and-forget so the caller is not suspended until the user closes the
     * popup - the caller (e.g. the URI handler) can run any follow-up code, such as resetting
     * the busy state, immediately. The popups are serialized via [popupMutex] so they are
     * shown one after another rather than overwriting each other.
     */
    fun showInfoPopup(title: String, text: String) {
        cs.launch(CoroutineName("popup")) {
            try {
                ui.showInfoPopup(
                    i18n.pnotr(title),
                    i18n.pnotr(text),
                    i18n.ptrl("OK")
                )
            } catch (_: CancellationException) {
                // Expected when the plugin scope shuts down while the popup is open.
            } catch (ex: Exception) {
                logger.error(ex, "Failed to display popup with title '$title'")
            }
        }
    }

    fun popupPluginMainPage() {
        this.ui.showWindow()
        this.envPageManager.showPluginEnvironmentsPage(false)
    }
}
