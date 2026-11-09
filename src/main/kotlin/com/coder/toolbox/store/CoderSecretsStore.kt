package com.coder.toolbox.store

import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.util.origin
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.StoredOAuthSession
import com.jetbrains.toolbox.api.core.PluginSecretStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    fun apiTokenFor(url: URL): String? = apiSessionKey(url)?.let { store[it] }

    fun storeApiTokenFor(url: URL, apiToken: String) {
        val key = requireNotNull(apiSessionKey(url)) { "Session credentials require an HTTP(S) deployment URL" }
        store[key] = apiToken
    }

    private fun apiSessionKey(url: URL): String? =
        url.origin()?.let { "api-session:$it" }

    fun isUrlTrusted(url: URL): Boolean {
        val requested = url.toString().toHttpUrlOrNull() ?: return false
        val origin = requested.origin()
        val saved = store["deployment-url:$origin"]
        return if (saved != null) {
            saved.toHttpUrlOrNull() == requested
        } else {
            // Older origin-bound tokens have no record of a deployment path.
            requested.toString() == "$origin/" && !apiTokenFor(url).isNullOrBlank()
        }
    }

    fun trustDeployment(url: URL) {
        val address = requireNotNull(url.toString().toHttpUrlOrNull()) { "Deployment trust requires an HTTP(S) URL" }
        val key = "deployment-url:${address.origin()}"
        if (store[key]?.toHttpUrlOrNull() != address) store[key] = url.toString()
    }

    fun clearSessionFor(url: URL) {
        apiSessionKey(url)?.let { store.clear(it) }
        url.origin()?.let { origin ->
            val key = "deployment-url:$origin"
            val signedInAt = store[key]
            val savedOrigin = signedInAt?.toHttpUrlOrNull()?.origin()
            if (signedInAt != null && signedInAt != url.toString() && savedOrigin == origin) {
                clearOAuthSession(signedInAt)
            }
            store.clear(key)
        }
        clearOAuthSession(url.toString())
    }

    private fun clearOAuthSession(url: String) {
        listOf(
            OAUTH_CLIENT_ID_PREFIX, OAUTH_CLIENT_SECRET_PREFIX, OAUTH_REFRESH_TOKEN,
            OAUTH_TOKEN_AUTH_METHOD, OAUTH_TOKEN_ENDPOINT
        ).forEach { prefix ->
            store.clear("$prefix-$url")
        }
    }

    fun oauthSessionFor(url: String): StoredOAuthSession? {
        val address = oauthStorageAddress(url)
        return readOAuthSession(address) ?: if (address != url) readOAuthSession(url) else null
    }

    private fun oauthStorageAddress(url: String): String {
        // Existing OAuth keys use the exact spelling of the URL used to sign in.
        val requested = url.toHttpUrlOrNull() ?: return url
        val saved = store["deployment-url:${requested.origin()}"]
        return saved?.takeIf { it.toHttpUrlOrNull() == requested } ?: url
    }

    private fun readOAuthSession(url: String): StoredOAuthSession? {
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
            val address = oauthStorageAddress(url)
            store["$OAUTH_CLIENT_ID_PREFIX-$address"] = oAuthSessionCtx.clientId
            store["$OAUTH_CLIENT_SECRET_PREFIX-$address"] = oAuthSessionCtx.clientSecret
            store["$OAUTH_REFRESH_TOKEN-$address"] = refreshToken
            store["$OAUTH_TOKEN_AUTH_METHOD-$address"] = oAuthSessionCtx.tokenAuthMethod.name
            store["$OAUTH_TOKEN_ENDPOINT-$address"] = oAuthSessionCtx.tokenEndpoint
        }
    }
}
