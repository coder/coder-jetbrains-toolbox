package com.coder.toolbox.views.state

import com.coder.toolbox.oauth.OAuthTokenResponse
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import java.net.URL

/**
 * Singleton that holds Coder setup wizard context (URL and token) across multiple
 * Toolbox window lifecycle events.
 *
 * This ensures that user input (URL and token) is not lost when the Toolbox
 * window is temporarily closed or recreated.
 */
object CoderSetupWizardContext {
    /**
     * The currently entered URL.
     */
    var url: URL? = null

    /**
     * The token associated with the URL.
     */
    var token: String? = null

    /**
     * The OAuth session context.
     */
    var oauthSession: CoderOAuthSessionContext? = null

    /**
     * Returns true if a URL is currently set.
     */
    fun hasUrl(): Boolean = url != null

    /**
     * Returns true if a token is currently set.
     */
    fun hasToken(): Boolean = !token.isNullOrBlank()

    /**
     * Returns true if an OAuth access token is currently set.
     */
    fun hasOAuthSession(): Boolean = oauthSession?.tokenResponse?.accessToken != null

    /**
     * Returns true if URL or token is missing and auth is not yet possible.
     */
    fun isNotReadyForAuth(): Boolean = !(hasUrl() && (hasToken() || hasOAuthSession()))

    /**
     * Resets both URL and token to null.
     */
    fun reset() {
        url = null
        token = null
        oauthSession = null
    }
}

data class CoderOAuthSessionContext(
    val clientId: String,
    val clientSecret: String,
    val tokenCodeVerifier: String,
    val state: String,
    val tokenEndpoint: String,
    var tokenResponse: OAuthTokenResponse? = null,
    val tokenAuthMethod: TokenEndpointAuthMethod
)

fun CoderOAuthSessionContext?.hasRefreshToken(): Boolean = this?.tokenResponse?.refreshToken != null