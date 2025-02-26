package com.coder.toolbox.plugin

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Small subset representation of extension.json
 */
@JsonClass(generateAdapter = true)
data class PluginInfo(
    @Json(name = "id") val id: String,
    @Json(name = "version") val version: String)
