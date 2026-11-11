package com.coder.toolbox.util

import com.jetbrains.toolbox.api.core.PluginSecretStore

internal class InMemoryPluginSecretStore : PluginSecretStore {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun clear(key: String) {
        values.remove(key)
    }
}
