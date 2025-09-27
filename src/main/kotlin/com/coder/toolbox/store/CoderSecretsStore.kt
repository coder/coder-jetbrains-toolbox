package com.coder.toolbox.store

import com.jetbrains.toolbox.api.core.PluginSecretStore
import java.net.URL


/**
 * Provides Coder secrets backed by the secrets store service.
 */
class CoderSecretsStore(private val store: PluginSecretStore) {
    @Deprecated(
        message = "The URL is now stored the JSON backed settings store. Use CoderSettingsStore#lastDeploymentURL",
        replaceWith = ReplaceWith("context.settingsStore.lastDeploymentURL")
    )
    val lastDeploymentURL: String = store["last-deployment-url"] ?: ""

    fun tokenFor(url: URL): String? = store[url.host]

    fun storeTokenFor(url: URL, token: String) {
        store[url.host] = token
    }
}
