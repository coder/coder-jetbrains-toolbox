package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json

enum class WorkspaceBuildReason {
    @Json(name = "jetbrains_connection") JETBRAINS_CONNECTION,
}