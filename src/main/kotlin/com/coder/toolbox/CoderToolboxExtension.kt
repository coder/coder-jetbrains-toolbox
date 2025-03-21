package com.coder.toolbox

import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.RemoteDevExtension
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * Entry point into the extension.
 */
class CoderToolboxExtension : RemoteDevExtension {
    // All services must be passed in here and threaded as necessary.
    override fun createRemoteProviderPluginInstance(serviceLocator: ServiceLocator): RemoteProvider {
        return CoderRemoteProvider(
            CoderToolboxContext(
                serviceLocator.getService(ToolboxUi::class.java),
                serviceLocator.getService(EnvironmentUiPageManager::class.java),
                serviceLocator.getService(EnvironmentStateColorPalette::class.java),
                serviceLocator.getService(ClientHelper::class.java),
                serviceLocator.getService(CoroutineScope::class.java),
                serviceLocator.getService(Logger::class.java),
                serviceLocator.getService(LocalizableStringFactory::class.java),
                serviceLocator.getService(PluginSettingsStore::class.java),
                serviceLocator.getService(PluginSecretStore::class.java),
            ),
            OkHttpClient(),
        )
    }
}