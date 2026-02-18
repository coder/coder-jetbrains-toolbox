package com.coder.toolbox.store

import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.jetbrains.toolbox.api.core.PluginSecretStore
import java.net.URL

private const val OAUTH_CLIENT_ID_PREFIX = "oauth-client-id"
private const val OAUTH_CLIENT_SECRET_PREFIX = "oauth-client-secret"
private const val OAUTH_REFRESH_TOKEN = "oauth-refresh-token"
private const val OAUTH_TOKEN_AUTH_METHOD = "oauth-token-auth-method"

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

    fun storeOAuthFor(url: String, oAuthSessionCtx: CoderOAuthSessionContext) {
        oAuthSessionCtx.tokenResponse?.refreshToken?.let { refreshToken ->
            store["$OAUTH_CLIENT_ID_PREFIX-$url"] = oAuthSessionCtx.clientId
            store["$OAUTH_CLIENT_SECRET_PREFIX-$url"] = oAuthSessionCtx.clientSecret
            store["$OAUTH_REFRESH_TOKEN-$url"] = refreshToken
            store["$OAUTH_TOKEN_AUTH_METHOD-$url"] = oAuthSessionCtx.tokenAuthMethod.name
        }
    }
}
