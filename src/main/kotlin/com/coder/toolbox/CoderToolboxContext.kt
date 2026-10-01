package com.coder.toolbox

import com.coder.toolbox.diagnostics.CoderLogger
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
import kotlinx.coroutines.CoroutineScope
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
    private val underlyingLogger: Logger,
    val i18n: LocalizableStringFactory,
    val settingsStore: CoderSettingsStore,
    val secrets: CoderSecretsStore,
    val proxySettings: ToolboxProxySettings,
) {
    val logger: CoderLogger = CoderLogger(underlyingLogger, ui, cs, i18n)
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

    fun popupPluginMainPage() {
        this.ui.showWindow()
        this.envPageManager.showPluginEnvironmentsPage(false)
    }
}
