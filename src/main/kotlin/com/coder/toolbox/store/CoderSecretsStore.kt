package com.coder.toolbox.store

import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.StoredOAuthSession
import com.jetbrains.toolbox.api.core.PluginSecretStore
import java.net.URL

private const val OAUTH_CLIENT_ID_PREFIX = "oauth-client-id"
private const val OAUTH_CLIENT_SECRET_PREFIX = "oauth-client-secret"
private const val OAUTH_REFRESH_TOKEN = "oauth-refresh-token"
private const val OAUTH_TOKEN_AUTH_METHOD = "oauth-token-auth-method"
private const val OAUTH_TOKEN_ENDPOINT = "oauth-token-endpoint"

/**
 * Provides Coder secrets backed by the secrets store service.
 */
class CoderSecretsStore(private val store: PluginSecretStore) {
    @Deprecated(
        message = "The URL is now stored the JSON backed settings store. Use CoderSettingsStore#lastDeploymentURL",
        replaceWith = ReplaceWith("context.settingsStore.lastDeploymentURL")
    )
    val lastDeploymentURL: String = store["last-deployment-url"] ?: ""

    fun apiTokenFor(url: URL): String? = store[url.host]

    fun storeApiTokenFor(url: URL, apiToken: String) {
        store[url.host] = apiToken
    }

    fun oauthSessionFor(url: String): StoredOAuthSession? {
        val clientId = store["$OAUTH_CLIENT_ID_PREFIX-$url"]
        val clientSecret = store["$OAUTH_CLIENT_SECRET_PREFIX-$url"]
        val refreshToken = store["$OAUTH_REFRESH_TOKEN-$url"]
        val tokenAuthMethod = store["$OAUTH_TOKEN_AUTH_METHOD-$url"]
        val tokenEndpoint = store["$OAUTH_TOKEN_ENDPOINT-$url"]
        if (clientId == null || clientSecret == null || refreshToken == null || tokenAuthMethod == null || tokenEndpoint == null) {
            return null
        }

        return StoredOAuthSession(
            clientId = clientId,
            clientSecret = clientSecret,
            refreshToken = refreshToken,
            tokenAuthMethod = TokenEndpointAuthMethod.valueOf(tokenAuthMethod),
            tokenEndpoint = tokenEndpoint
        )
    }

    fun storeOAuthFor(url: String, oAuthSessionCtx: CoderOAuthSessionContext) {
        oAuthSessionCtx.tokenResponse?.refreshToken?.let { refreshToken ->
            store["$OAUTH_CLIENT_ID_PREFIX-$url"] = oAuthSessionCtx.clientId
            store["$OAUTH_CLIENT_SECRET_PREFIX-$url"] = oAuthSessionCtx.clientSecret
            store["$OAUTH_REFRESH_TOKEN-$url"] = refreshToken
            store["$OAUTH_TOKEN_AUTH_METHOD-$url"] = oAuthSessionCtx.tokenAuthMethod.name
            store["$OAUTH_TOKEN_ENDPOINT-$url"] = oAuthSessionCtx.tokenEndpoint
        }
    }
}
