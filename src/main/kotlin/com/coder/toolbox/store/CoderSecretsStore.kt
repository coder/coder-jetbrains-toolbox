package com.coder.toolbox.store

import com.jetbrains.toolbox.api.core.PluginSecretStore


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

    var lastDeploymentURL: String
        get() = get("last-deployment-url")
        set(value) = set("last-deployment-url", value)
    var lastToken: String
        get() = get("last-token")
        set(value) = set("last-token", value)
    var rememberMe: Boolean
        get() = get("remember-me").toBoolean()
        set(value) = set("remember-me", value.toString())
}
