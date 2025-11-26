package com.coder.toolbox.oauth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DCR response
 */
@JsonClass(generateAdapter = true)
data class ClientRegistrationResponse(
    @field:Json(name = "client_id") val clientId: String,
    @field:Json(name = "client_secret") val clientSecret: String,
    @field:Json(name = "client_name") val clientName: String,
    @field:Json(name = "redirect_uris") val redirectUris: List<String>,
    @field:Json(name = "grant_types") val grantTypes: List<String>,
    @field:Json(name = "response_types") val responseTypes: List<String>,
    @field:Json(name = "scope") val scope: String,
    @field:Json(name = "token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
    @field:Json(name = "client_id_issued_at") val clientIdIssuedAt: Long?,
    @field:Json(name = "client_secret_expires_at") val clientSecretExpiresAt: Long?,
    @field:Json(name = "registration_client_uri") val registrationClientUri: String,
    @field:Json(name = "registration_access_token") val registrationAccessToken: String
)