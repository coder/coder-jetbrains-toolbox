package com.coder.toolbox.oauth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ClientRegistrationRequest(
    @field:Json(name = "client_name") val clientName: String,
    @field:Json(name = "redirect_uris") val redirectUris: List<String>,
    @field:Json(name = "grant_types") val grantTypes: List<String>,
    @field:Json(name = "response_types") val responseTypes: List<String>,
    @field:Json(name = "scope") val scope: String,
    @field:Json(name = "token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null
)
