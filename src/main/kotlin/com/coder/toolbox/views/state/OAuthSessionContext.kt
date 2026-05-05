package com.coder.toolbox.views.state

import com.coder.toolbox.oauth.OAuthTokenResponse
import com.coder.toolbox.oauth.TokenEndpointAuthMethod

data class CoderOAuthSessionContext(
    val clientId: String,
    val clientSecret: String,
    val tokenCodeVerifier: String,
    val state: String,
    val tokenEndpoint: String,
    val tokenResponse: OAuthTokenResponse? = null,
    val tokenAuthMethod: TokenEndpointAuthMethod
)

data class StoredOAuthSession(
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String,
    val tokenAuthMethod: TokenEndpointAuthMethod,
    val tokenEndpoint: String
)

fun CoderOAuthSessionContext?.hasRefreshToken(): Boolean = this?.tokenResponse?.refreshToken != null