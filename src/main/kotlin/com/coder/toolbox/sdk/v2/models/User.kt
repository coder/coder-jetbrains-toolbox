package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class User(
    @Json(name = "id") val id: UUID,
    @Json(name = "username") val username: String,
)
