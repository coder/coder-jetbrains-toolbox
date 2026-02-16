package com.coder.toolbox

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.ConnectionMonitoringService
import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.getService
import com.jetbrains.toolbox.api.core.os.LocalDesktopManager
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.RemoteDevExtension
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import com.jetbrains.toolbox.api.remoteDev.connection.ToolboxProxySettings
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import kotlinx.coroutines.CoroutineScope

/**
 * Entry point into the extension.
 */
class CoderToolboxExtension : RemoteDevExtension {
    // All services must be passed in here and threaded as necessary.
    override fun createRemoteProviderPluginInstance(serviceLocator: ServiceLocator): RemoteProvider {
        val ui = serviceLocator.getService<ToolboxUi>()
        val logger = serviceLocator.getService(Logger::class.java)
        val cs = serviceLocator.getService<CoroutineScope>()
        val i18n = serviceLocator.getService<LocalizableStringFactory>()
        return CoderRemoteProvider(
            CoderToolboxContext(
                ui,
                serviceLocator.getService<EnvironmentUiPageManager>(),
                serviceLocator.getService<EnvironmentStateColorPalette>(),
                serviceLocator.getService<RemoteToolsHelper>(),
                serviceLocator.getService<ClientHelper>(),
                serviceLocator.getService<LocalDesktopManager>(),
                cs,
                serviceLocator.getService<Logger>(),
                i18n,
                CoderSettingsStore(serviceLocator.getService<PluginSettingsStore>(), Environment(), logger),
                CoderSecretsStore(serviceLocator.getService<PluginSecretStore>()),
                serviceLocator.getService<ToolboxProxySettings>(),
                ConnectionMonitoringService(
                    cs,
                    ui,
                    logger,
                    i18n
                )
            )
        )
    }
}