package com.coder.toolbox

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.getService
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.RemoteDevExtension
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
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
        val logger = serviceLocator.getService(Logger::class.java)
        return CoderRemoteProvider(
            CoderToolboxContext(
                serviceLocator.getService<ToolboxUi>(),
                serviceLocator.getService<EnvironmentUiPageManager>(),
                serviceLocator.getService<EnvironmentStateColorPalette>(),
                serviceLocator.getService<ClientHelper>(),
                serviceLocator.getService<CoroutineScope>(),
                serviceLocator.getService<Logger>(),
                serviceLocator.getService<LocalizableStringFactory>(),
                CoderSettingsStore(serviceLocator.getService<PluginSettingsStore>(), Environment(), logger),
                CoderSecretsStore(serviceLocator.getService<PluginSecretStore>()),
                serviceLocator.getService<ToolboxProxySettings>()
            )
        )
    }
}