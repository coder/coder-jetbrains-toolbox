package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.time.Instant

@JsonClass(generateAdapter = true)
data class ProvisionerJobLog(
    @property:Json(name = "id") val id: Long,
    @property:Json(name = "created_at") val createdAt: Instant,
    @property:Json(name = "log_source") val source: String,
    @property:Json(name = "log_level") val level: String,
    @property:Json(name = "stage") val stage: String,
    @property:Json(name = "output") val output: String,
)