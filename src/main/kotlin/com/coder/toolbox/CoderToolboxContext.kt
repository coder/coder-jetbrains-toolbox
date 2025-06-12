package com.coder.toolbox

import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.toURL
import com.coder.toolbox.views.CoderPage
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
import kotlinx.coroutines.delay
import java.net.URL
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

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
     * 1. Last used URL.
     * 2. URL in settings.
     * 3. CODER_URL.
     * 4. URL in global cli config.
     */
    val deploymentUrl: URL
        get() {
            if (this.secrets.lastDeploymentURL.isNotBlank()) {
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

    /**
     * Forces the title bar on the main page to be refreshed
     */
    suspend fun refreshMainPage() {
        // the url/title on the main page is only refreshed if
        // we're navigating to the main env page from another page.
        // If TBX is already on the main page the title is not refreshed
        // hence we force a navigation from a blank page.
        ui.showUiPage(CoderPage.emptyPage(this))


        // Toolbox uses an internal shared flow with a buffer of 4 items and a DROP_OLDEST strategy.
        // Both showUiPage and showPluginEnvironmentsPage send events to this flow.
        // If we emit two events back-to-back, the first one often gets dropped and only the second is shown.
        // To reduce this risk, we add a small delay to let the UI coroutine process the first event.
        // Simply yielding the coroutine isn't reliable, especially right after Toolbox starts via URI handling.
        // Based on my testing, a 5–10 ms delay is enough to ensure the blank page is processed,
        // while still short enough to be invisible to users.
        delay(10.milliseconds)
        envPageManager.showPluginEnvironmentsPage()
    }
}
