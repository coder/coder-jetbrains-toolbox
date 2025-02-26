package com.coder.toolbox.plugin

import com.squareup.moshi.Moshi
import java.io.InputStream

object PluginManager {

    val pluginInfo: PluginInfo by lazy {
        loadPluginMetadata()
    }

    private fun loadPluginMetadata(): PluginInfo {
        val resourcePath = "/extension.json"
        val inputStream: InputStream? = PluginManager.javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        if (inputStream == null) {
            throw IllegalStateException("Can't load plugin information")
        }

        inputStream.use { stream ->
            val jsonContent = stream.bufferedReader().readText()
            return Moshi.Builder().build().adapter(PluginInfo::class.java).fromJson(jsonContent)
                ?: throw IllegalArgumentException("Failed to parse JSON")
        }
    }
}