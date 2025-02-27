package com.coder.toolbox

import com.coder.toolbox.logger.CoderLoggerFactory
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.remoteDev.RemoteDevExtension
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import okhttp3.OkHttpClient

/**
 * Entry point into the extension.
 */
class CoderToolboxExtension : RemoteDevExtension {
    // All services must be passed in here and threaded as necessary.
    override fun createRemoteProviderPluginInstance(serviceLocator: ServiceLocator): RemoteProvider {
        // initialize logger factory
        CoderLoggerFactory.tLogger = serviceLocator.getService(Logger::class.java)

        return CoderRemoteProvider(
            serviceLocator,
            OkHttpClient(),
        )
    }
}