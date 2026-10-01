package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApiErrorResponse(
    @Json(name = "message") val message: String,
    @Json(name = "detail") val detail: String?,
    @Json(name = "validations") val validations: List<FieldValidation>? = null,
)

@JsonClass(generateAdapter = true)
data class FieldValidation(
    @Json(name = "field") val field: String,
    @Json(name = "detail") val detail: String,
)
