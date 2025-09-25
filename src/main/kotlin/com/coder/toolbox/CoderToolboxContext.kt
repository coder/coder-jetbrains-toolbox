package com.coder.toolbox

import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
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
import kotlinx.coroutines.CoroutineScope
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
            if (!this.settingsStore.lastDeploymentURL.isNullOrBlank()) {
                return this.settingsStore.lastDeploymentURL!!.toURL()
            } else if (this.secrets.lastDeploymentURL.isNotBlank()) {
                return this.secrets.lastDeploymentURL.toURL()
            }
            return this.settingsStore.defaultURL.toURL()
        }

    suspend fun logAndShowError(title: String, error: String) {
        logger.error(error)
        ui.showSnackbar(
            UUID.randomUUID().toString(),
            i18n.pnotr(title),
            i18n.pnotr(error),
            i18n.ptrl("OK")
        )
    }

    suspend fun logAndShowError(title: String, error: String, exception: Exception) {
        logger.error(exception, error)
        ui.showSnackbar(
            UUID.randomUUID().toString(),
            i18n.pnotr(title),
            i18n.pnotr(error),
            i18n.ptrl("OK")
        )
    }

    suspend fun logAndShowWarning(title: String, warning: String) {
        logger.warn(warning)
        ui.showSnackbar(
            UUID.randomUUID().toString(),
            i18n.pnotr(title),
            i18n.pnotr(warning),
            i18n.ptrl("OK")
        )
    }

    suspend fun logAndShowInfo(title: String, info: String) {
        logger.info(info)
        ui.showSnackbar(
            UUID.randomUUID().toString(),
            i18n.pnotr(title),
            i18n.pnotr(info),
            i18n.ptrl("OK")
        )
    }

    fun popupPluginMainPage() {
        this.ui.showWindow()
        this.envPageManager.showPluginEnvironmentsPage(true)
    }
}
