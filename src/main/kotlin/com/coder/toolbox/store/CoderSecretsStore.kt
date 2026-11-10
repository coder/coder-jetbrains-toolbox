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
private const val LAST_DEPLOYMENT_URL_KEY = "last-deployment-url"

/**
 * Provides Coder secrets backed by the secrets store service.
 */
class CoderSecretsStore(private val store: PluginSecretStore) {
    @Deprecated(
        message = "The URL is now stored the JSON backed settings store. Use CoderSettingsStore#lastDeploymentURL",
        replaceWith = ReplaceWith("context.settingsStore.lastDeploymentURL")
    )
    val lastDeploymentURL: String = store[LAST_DEPLOYMENT_URL_KEY] ?: ""

    fun apiTokenFor(url: URL): String? = apiSessionKey(url)?.let { store[it] }

    fun storeApiTokenFor(url: URL, apiToken: String) {
        val key = requireNotNull(apiSessionKey(url)) { "Session credentials require an HTTP(S) deployment URL" }
        store[key] = apiToken
        clearLegacyApiToken(url)
    }

    private fun apiSessionKey(url: URL): String? =
        url.origin()?.let { "api-session:$it" }

    private fun clearLegacyApiToken(url: URL) {
        if (url.host.isEmpty()) return
        // Older versions used URL.host verbatim. A link can also supply the host in lowercase.
        for (host in setOf(url.host, url.host.lowercase())) {
            // A single-label hostname can share the key used for the last deployment URL.
            if (host == LAST_DEPLOYMENT_URL_KEY && store[host]?.toHttpUrlOrNull() != null) continue
            store.clear(host)
        }
    }

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

    @Synchronized
    fun clearSessionFor(url: URL) {
        apiSessionKey(url)?.let {
            store.clear(it)
            clearLegacyApiToken(url)
        }
        url.origin()?.let { origin ->
            clearOAuthSession(origin)
            legacyOAuthAddresses(url.toString(), origin).forEach(::clearOAuthSession)
            store.clear("deployment-url:$origin")
        }
    }

    private fun clearOAuthSession(url: String) {
        listOf(
            OAUTH_CLIENT_ID_PREFIX, OAUTH_CLIENT_SECRET_PREFIX, OAUTH_REFRESH_TOKEN,
            OAUTH_TOKEN_AUTH_METHOD, OAUTH_TOKEN_ENDPOINT
        ).forEach { prefix ->
            store.clear("$prefix-$url")
        }
    }

    // Older versions used the URL exactly as entered, including its path or trailing slash.
    private fun legacyOAuthAddresses(url: String, origin: String): Set<String> =
        setOf(url, "$origin/") - origin

    @Synchronized
    fun oauthSessionFor(url: String): StoredOAuthSession? {
        val origin = url.toHttpUrlOrNull()?.origin() ?: return null
        readOAuthSession(origin)?.let { return it }
        val (legacyUrl, legacy) = legacyOAuthAddresses(url, origin)
            .firstNotNullOfOrNull { address -> readOAuthSession(address)?.let { address to it } } ?: return null
        // Move the session on first use so later lookups and logout need only the origin.
        runCatching {
            writeOAuthSession(origin, legacy)
            clearOAuthSession(legacyUrl)
        }
        return legacy
    }

    private fun readOAuthSession(url: String): StoredOAuthSession? {
        val clientId = store["$OAUTH_CLIENT_ID_PREFIX-$url"] ?: return null
        val clientSecret = store["$OAUTH_CLIENT_SECRET_PREFIX-$url"]
        val refreshToken = store["$OAUTH_REFRESH_TOKEN-$url"]
        val tokenAuthMethod = store["$OAUTH_TOKEN_AUTH_METHOD-$url"]
        val tokenEndpoint = store["$OAUTH_TOKEN_ENDPOINT-$url"]
        if (clientSecret == null || refreshToken == null || tokenAuthMethod == null || tokenEndpoint == null) {
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

    private fun writeOAuthSession(origin: String, session: StoredOAuthSession) {
        if (readOAuthSession(origin)?.copy(refreshToken = session.refreshToken) == session) {
            store["$OAUTH_REFRESH_TOKEN-$origin"] = session.refreshToken
            return
        }
        // Publish a new registration's client ID last so partial writes cannot be read as a session.
        store.clear("$OAUTH_CLIENT_ID_PREFIX-$origin")
        store["$OAUTH_CLIENT_SECRET_PREFIX-$origin"] = session.clientSecret
        store["$OAUTH_REFRESH_TOKEN-$origin"] = session.refreshToken
        store["$OAUTH_TOKEN_AUTH_METHOD-$origin"] = session.tokenAuthMethod.name
        store["$OAUTH_TOKEN_ENDPOINT-$origin"] = session.tokenEndpoint
        store["$OAUTH_CLIENT_ID_PREFIX-$origin"] = session.clientId
    }

    @Synchronized
    fun storeOAuthFor(url: String, oAuthSessionCtx: CoderOAuthSessionContext) {
        val refreshToken = oAuthSessionCtx.tokenResponse?.refreshToken ?: return
        val origin =
            requireNotNull(url.toHttpUrlOrNull()?.origin()) { "OAuth credentials require an HTTP(S) deployment URL" }
        writeOAuthSession(
            origin, StoredOAuthSession(
                clientId = oAuthSessionCtx.clientId,
                clientSecret = oAuthSessionCtx.clientSecret,
                refreshToken = refreshToken,
                tokenAuthMethod = oAuthSessionCtx.tokenAuthMethod,
                tokenEndpoint = oAuthSessionCtx.tokenEndpoint,
            )
        )
        legacyOAuthAddresses(url, origin).forEach(::clearOAuthSession)
    }
}
