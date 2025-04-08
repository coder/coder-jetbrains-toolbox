package com.coder.toolbox

import com.coder.toolbox.settings.SettingSource
import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.toURL
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import kotlinx.coroutines.CoroutineScope

data class CoderToolboxContext(
    val ui: ToolboxUi,
    val envPageManager: EnvironmentUiPageManager,
    val envStateColorPalette: EnvironmentStateColorPalette,
    val ideOrchestrator: ClientHelper,
    val cs: CoroutineScope,
    val logger: Logger,
    val i18n: LocalizableStringFactory,
    val settingsStore: CoderSettingsStore,
    val secrets: CoderSecretsStore
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
    val deploymentUrl: Pair<String, SettingSource>?
        get() = this.secrets.lastDeploymentURL.let {
            if (it.isNotBlank()) {
                it to SettingSource.LAST_USED
            } else {
                this.settingsStore.defaultURL()
            }
        }

    /**
     * Try to find a token.
     *
     * Order of preference:
     *
     * 1. Last used token, if it was for this deployment.
     * 2. Token on disk for this deployment.
     * 3. Global token for Coder, if it matches the deployment.
     */
    fun getToken(deploymentURL: String?): Pair<String, SettingSource>? = this.secrets.lastToken.let {
        if (it.isNotBlank() && this.secrets.lastDeploymentURL == deploymentURL) {
            it to SettingSource.LAST_USED
        } else {
            if (deploymentURL != null) {
                this.settingsStore.token(deploymentURL.toURL())
            } else null
        }
    }

}
