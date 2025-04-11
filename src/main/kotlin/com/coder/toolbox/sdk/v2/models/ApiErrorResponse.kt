package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApiErrorResponse(
    @Json(name = "message") val message: String,
    @Json(name = "detail") val detail: String?,
)
