package com.coder.toolbox.util

import com.jetbrains.toolbox.api.core.PluginSettingsStore


fun pluginTestSettingsStore(): PluginSettingsStore = PluginTestSettingsStoreImpl()

fun pluginTestSettingsStore(vararg pairs: Pair<String, String>): PluginSettingsStore =
    PluginTestSettingsStoreImpl().apply {
        putAll(pairs)
    }

private class PluginTestSettingsStoreImpl(
    private val backingMap: MutableMap<String, String> = HashMap()
) : PluginSettingsStore, MutableMap<String, String> by backingMap