package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class WorkspaceWatchEvent(
    @property:Json(name = "type") val type: String,
    @property:Json(name = "data") val data: Workspace? = null,
)
