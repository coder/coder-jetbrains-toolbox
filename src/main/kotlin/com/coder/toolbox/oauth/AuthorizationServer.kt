package com.coder.toolbox.oauth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AuthorizationServer(
    @field:Json(name = "authorization_endpoint") val authorizationEndpoint: String,
    @field:Json(name = "token_endpoint") val tokenEndpoint: String,
    @field:Json(name = "registration_endpoint") val registrationEndpoint: String,
    @property:Json(name = "response_types_supported") val supportedResponseTypes: List<String>,
    @property:Json(name = "token_endpoint_auth_methods_supported") val authMethodForTokenEndpoint: List<TokenEndpointAuthMethod>,
)

enum class TokenEndpointAuthMethod {
    @Json(name = "none")
    NONE,

    @Json(name = "client_secret_post")
    CLIENT_SECRET_POST,

    @Json(name = "client_secret_basic")
    CLIENT_SECRET_BASIC,
}

fun List<TokenEndpointAuthMethod>.getPreferredOrAvailable(): TokenEndpointAuthMethod {
    return when {
        // secret basic is preferred by coder
        TokenEndpointAuthMethod.CLIENT_SECRET_BASIC in this -> TokenEndpointAuthMethod.CLIENT_SECRET_BASIC
        TokenEndpointAuthMethod.CLIENT_SECRET_POST in this -> TokenEndpointAuthMethod.CLIENT_SECRET_POST
        else -> TokenEndpointAuthMethod.NONE

    }
}