package com.coder.toolbox.store

import com.jetbrains.toolbox.api.core.PluginSecretStore
import java.net.URL


/**
 * Provides Coder secrets backed by the secrets store service.
 */
class CoderSecretsStore(private val store: PluginSecretStore) {
    private fun get(key: String): String = store[key] ?: ""

    private fun set(key: String, value: String) {
        if (value.isBlank()) {
            store.clear(key)
        } else {
            store[key] = value
        }
    }

    val lastDeploymentURL: String
        get() = get("last-deployment-url")
    var lastToken: String
        get() = get("last-token")
        set(value) = set("last-token", value)

    fun tokenFor(url: URL): String? = store[url.host]

    fun storeTokenFor(url: URL, token: String) {
        store[url.host] = token
    }
}
