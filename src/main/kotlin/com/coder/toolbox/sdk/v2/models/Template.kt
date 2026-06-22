package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class Template(
    @Json(name = "id") val id: UUID,
    @Json(name = "active_version_id") val activeVersionID: UUID,
    @Json(name = "name") val name: String,
    @Json(name = "display_name") val displayName: String,
    @Json(name = "deprecated") val deprecated: Boolean,
)
