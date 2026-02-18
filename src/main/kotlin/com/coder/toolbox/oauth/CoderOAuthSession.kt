package com.coder.toolbox.oauth

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CoderOAuthSession(
    val clientId: String,
    val clientSecret: String,
    val tokenCodeVerifier: String,
    val state: String,
    val tokenEndpoint: String,
    var accessToken: String? = null,
    var refreshToken: String? = null,
    val tokenAuthMethod: TokenEndpointAuthMethod
)
