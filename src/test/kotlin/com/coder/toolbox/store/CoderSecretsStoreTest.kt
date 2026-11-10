package com.coder.toolbox.store

import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.util.toURL
import com.coder.toolbox.views.state.StoredOAuthSession
import com.coder.toolbox.views.state.toSessionContext
import com.jetbrains.toolbox.api.core.PluginSecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoderSecretsStoreTest {
    private val values = mutableMapOf<String, String>()
    private val backing = object : PluginSecretStore {
        override fun get(key: String): String? = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }

        override fun clear(key: String) {
            values.remove(key)
        }
    }
    private val secrets = CoderSecretsStore(backing)

    @Test
    fun `saved API tokens are reused only for the same origin after restarting`() {
        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "token")
        val reopened = CoderSecretsStore(backing)

        for (url in listOf("https://coder.example.com/", "https://CODER.example.com:443/path")) {
            assertEquals("token", reopened.apiTokenFor(url.toURL()), url)
        }
        for (url in listOf("http://coder.example.com", "https://coder.example.com:8443", "https://other.example.com")) {
            assertNull(reopened.apiTokenFor(url.toURL()), url)
        }
    }

    @Test
    fun `HTTP default ports IPv6 and international hostnames use canonical origins`() {
        for ((original, equivalent) in listOf(
            "http://coder.example.com" to "http://coder.example.com:80/",
            "https://[::1]:8443" to "https://[0:0:0:0:0:0:0:1]:8443/",
            "https://ほげ.example" to "https://xn--18j4d.example:443/",
        )) {
            secrets.storeApiTokenFor(original.toURL(), "token")
            assertEquals("token", secrets.apiTokenFor(equivalent.toURL()), original)
        }
    }

    @Test
    fun `hostname-only credentials are ignored until a new token is saved`() {
        values["coder.example.com"] = "old-token"
        values["last-deployment-url"] = "https://coder.example.com"

        assertNull(secrets.apiTokenFor("https://coder.example.com".toURL()))
        assertNull(secrets.apiTokenFor("http://coder.example.com".toURL()))
        assertFalse(secrets.isUrlTrusted("https://coder.example.com".toURL()))
        assertEquals("old-token", values["coder.example.com"])

        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "new-token")
        assertEquals("new-token", secrets.apiTokenFor("https://coder.example.com".toURL()))
        assertNull(values["coder.example.com"])
        assertEquals("https://coder.example.com", values["last-deployment-url"])
    }

    @Test
    fun `a failed replacement write preserves the hostname-only credential`() {
        values["coder.example.com"] = "old-token"
        val failingStore = object : PluginSecretStore by backing {
            override fun set(key: String, value: String) = throw IllegalStateException("Keychain is locked")
        }

        assertFailsWith<IllegalStateException> {
            CoderSecretsStore(failingStore).storeApiTokenFor("https://coder.example.com".toURL(), "new-token")
        }
        assertEquals("old-token", values["coder.example.com"])
    }

    @Test
    fun `logout clears a legacy token even without an origin-bound token`() {
        values["CODER.example.com"] = "old-token"
        values["other.example.com"] = "other-token"

        secrets.clearSessionFor("https://CODER.example.com:8443".toURL())

        assertNull(values["CODER.example.com"])
        assertEquals("other-token", values["other.example.com"])
    }

    @Test
    fun `a hostname matching the last deployment key preserves the stored URL`() {
        values["last-deployment-url"] = "https://coder.example.com"
        val url = "https://last-deployment-url".toURL()

        secrets.storeApiTokenFor(url, "new-token")
        secrets.clearSessionFor(url)

        assertEquals("https://coder.example.com", values["last-deployment-url"])
    }

    @Test
    fun `sessions on the same host with different schemes and ports coexist`() {
        secrets.storeApiTokenFor("http://coder.example.com".toURL(), "http-token")
        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "https-token")
        secrets.storeApiTokenFor("https://coder.example.com:8443".toURL(), "other-port-token")
        val reopened = CoderSecretsStore(backing)

        assertEquals("http-token", reopened.apiTokenFor("http://coder.example.com".toURL()))
        assertEquals("https-token", reopened.apiTokenFor("https://coder.example.com".toURL()))
        assertEquals("other-port-token", reopened.apiTokenFor("https://coder.example.com:8443".toURL()))

        reopened.storeApiTokenFor("https://CODER.example.com:443/".toURL(), "refreshed-token")

        assertEquals("http-token", secrets.apiTokenFor("http://coder.example.com".toURL()))
        assertEquals("refreshed-token", secrets.apiTokenFor("https://coder.example.com".toURL()))
        assertEquals("other-port-token", secrets.apiTokenFor("https://coder.example.com:8443".toURL()))
    }

    @Test
    fun `logout removes only the matching origin session`() {
        secrets.storeApiTokenFor("http://coder.example.com".toURL(), "http-token")
        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "https-token")
        secrets.storeApiTokenFor("https://coder.example.com:8443".toURL(), "other-port-token")

        secrets.clearSessionFor("https://CODER.example.com:443/path".toURL())

        assertEquals("http-token", secrets.apiTokenFor("http://coder.example.com".toURL()))
        assertNull(secrets.apiTokenFor("https://coder.example.com".toURL()))
        assertEquals("other-port-token", secrets.apiTokenFor("https://coder.example.com:8443".toURL()))
    }

    @Test
    fun `URLs without a valid HTTP origin cannot read or store tokens`() {
        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "existing-token")

        assertNull(secrets.apiTokenFor("file:///tmp/coder".toURL()))
        assertNull(secrets.apiTokenFor("https://coder.example.com:99999".toURL()))
        assertFailsWith<IllegalArgumentException> {
            secrets.storeApiTokenFor("file:///tmp/coder".toURL(), "invalid-token")
        }
        assertFailsWith<IllegalArgumentException> {
            secrets.storeApiTokenFor("https://coder.example.com:99999".toURL(), "invalid-token")
        }

        assertEquals("existing-token", secrets.apiTokenFor("https://coder.example.com".toURL()))
    }

    @Test
    fun `signing in trusts only that deployment origin across restarts`() {
        secrets.trustDeployment("https://coder1.example.com/".toURL())
        val reopened = CoderSecretsStore(backing)

        assertTrue(reopened.isUrlTrusted("https://CODER1.example.com:443".toURL()))
        assertFalse(reopened.isUrlTrusted("https://coder2.example.com/".toURL()))
        assertFalse(reopened.isUrlTrusted("http://coder1.example.com/".toURL()))
        assertFalse(reopened.isUrlTrusted("https://coder1.example.com:8443/".toURL()))
    }

    @Test
    fun `an origin-bound token establishes trust only for its origin`() {
        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "saved-token")

        assertTrue(secrets.isUrlTrusted("https://coder.example.com/".toURL()))
        assertFalse(secrets.isUrlTrusted("https://coder.example.com/other/".toURL()))
        assertFalse(secrets.isUrlTrusted("http://coder.example.com/".toURL()))
    }

    @Test
    fun `a sign-in at a configured base path does not silently trust the root`() {
        secrets.storeApiTokenFor("https://coder.example.com/team/".toURL(), "token")
        secrets.trustDeployment("https://coder.example.com/team/".toURL())

        assertTrue(secrets.isUrlTrusted("https://coder.example.com/team/".toURL()))
        assertFalse(secrets.isUrlTrusted("https://coder.example.com/".toURL()))
    }

    @Test
    fun `OAuth sessions use the normalized origin across restarts and refreshes`() {
        val signedInAt = "https://CODER.example.com:443/team/"
        val session = oauthSession()
        secrets.storeOAuthFor(signedInAt, session.toSessionContext())
        assertEquals("refresh-token", values["oauth-refresh-token-https://coder.example.com"])
        assertFalse(values.keys.any { it.endsWith(signedInAt) })

        val reopened = CoderSecretsStore(backing)
        assertEquals(session, reopened.oauthSessionFor("https://coder.example.com"))
        assertEquals(session, reopened.oauthSessionFor("https://coder.example.com/"))
        assertEquals(session, reopened.oauthSessionFor("https://coder.example.com/other/"))
        for (url in listOf("http://coder.example.com", "https://coder.example.com:8443", "https://other.example.com")) {
            assertNull(reopened.oauthSessionFor(url), url)
        }

        val refreshed = session.copy(refreshToken = "refreshed-token")
        reopened.storeOAuthFor("https://coder.example.com", refreshed.toSessionContext())
        assertEquals(refreshed, reopened.oauthSessionFor(signedInAt))

        reopened.clearSessionFor("https://coder.example.com/path".toURL())
        assertNull(reopened.oauthSessionFor(signedInAt))
    }

    private fun oauthSession() = StoredOAuthSession(
        clientId = "client-id",
        clientSecret = "client-secret",
        refreshToken = "refresh-token",
        tokenAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
        tokenEndpoint = "https://coder.example.com/team/oauth/token",
    )

    @Test
    fun `first lookup moves a legacy OAuth session from its exact URL to the origin`() {
        val oldUrl = "https://CODER.example.com:443/team/"
        val session = oauthSession()
        seedLegacyOAuth(oldUrl, session)

        assertEquals(session, secrets.oauthSessionFor(oldUrl))

        assertFalse(values.keys.any { it.endsWith(oldUrl) })
        assertEquals(session, CoderSecretsStore(backing).oauthSessionFor("https://coder.example.com/other"))
        secrets.clearSessionFor("https://coder.example.com".toURL())
        assertNull(secrets.oauthSessionFor(oldUrl))
    }

    private fun seedLegacyOAuth(url: String, session: StoredOAuthSession) {
        values["oauth-client-id-$url"] = session.clientId
        values["oauth-client-secret-$url"] = session.clientSecret
        values["oauth-refresh-token-$url"] = session.refreshToken
        values["oauth-token-auth-method-$url"] = session.tokenAuthMethod.name
        values["oauth-token-endpoint-$url"] = session.tokenEndpoint
    }

    @Test
    fun `remembering another base path leaves the OAuth session in place`() {
        val session = oauthSession()
        secrets.storeOAuthFor("https://coder.example.com/team/", session.toSessionContext())
        secrets.trustDeployment("https://coder.example.com/team/".toURL())

        secrets.trustDeployment("https://coder.example.com/other".toURL())

        assertEquals(session, CoderSecretsStore(backing).oauthSessionFor("https://coder.example.com/other"))
    }

    @Test
    fun `logout clears canonical and legacy OAuth keys without touching another origin`() {
        val origin = "https://coder.example.com"
        val other = "https://coder.example.com:8443"
        val session = oauthSession()
        seedLegacyOAuth("$origin/", session)
        assertEquals(session, secrets.oauthSessionFor(origin))
        seedLegacyOAuth(origin, session.copy(refreshToken = "canonical"))
        assertEquals("canonical", secrets.oauthSessionFor("$origin/")?.refreshToken)
        secrets.storeOAuthFor(other, session.toSessionContext())
        values["deployment-url:$origin"] = other

        secrets.clearSessionFor("$origin/team".toURL())

        assertTrue(values.keys.all { it.endsWith(other) })
        assertEquals(session, secrets.oauthSessionFor(other))
        assertNull(secrets.oauthSessionFor(origin))
    }

    @Test
    fun `failed migration leaves the legacy session available for retry`() {
        val url = "https://coder.example.com/"
        val session = oauthSession()
        seedLegacyOAuth(url, session)
        val failingStore = object : PluginSecretStore by backing {
            override fun set(key: String, value: String) {
                if (key == "oauth-token-endpoint-https://coder.example.com") error("Keychain is locked")
                backing[key] = value
            }
        }
        val refreshed = session.copy(refreshToken = "rotated")
        assertFailsWith<IllegalStateException> {
            CoderSecretsStore(failingStore).storeOAuthFor(url, refreshed.toSessionContext())
        }

        assertEquals(session, secrets.oauthSessionFor(url))
        secrets.storeOAuthFor(url, refreshed.toSessionContext())
        assertEquals(refreshed, secrets.oauthSessionFor(url))
    }

    @Test
    fun `lookup returns the legacy OAuth session when moving it fails`() {
        val url = "https://coder.example.com/"
        val session = oauthSession()
        seedLegacyOAuth(url, session)
        val failingStore = object : PluginSecretStore by backing {
            override fun set(key: String, value: String) = error("Keychain is locked")
        }

        assertEquals(session, CoderSecretsStore(failingStore).oauthSessionFor(url))
        assertEquals(session.refreshToken, values["oauth-refresh-token-$url"])
    }

    @Test
    fun `failed refresh token write preserves the current OAuth registration`() {
        val url = "https://coder.example.com"
        val session = oauthSession()
        secrets.storeOAuthFor(url, session.toSessionContext())
        val failingStore = object : PluginSecretStore by backing {
            override fun set(key: String, value: String) = error("Keychain is locked")
        }

        assertFailsWith<IllegalStateException> {
            CoderSecretsStore(failingStore).storeOAuthFor(
                url,
                session.copy(refreshToken = "rotated").toSessionContext()
            )
        }
        assertEquals(session, secrets.oauthSessionFor(url))
    }

    @Test
    fun `invalid origins cannot read or store OAuth credentials`() {
        for (url in listOf("file:///tmp/coder", "https://coder.example.com:99999")) {
            assertNull(secrets.oauthSessionFor(url))
            assertFailsWith<IllegalArgumentException> {
                secrets.storeOAuthFor(url, oauthSession().toSessionContext())
            }
        }
        assertTrue(values.isEmpty())
    }

    @Test
    fun `certificate and OAuth deployments can be remembered without an API token`() {
        secrets.trustDeployment("https://coder.example.com".toURL())

        val reopened = CoderSecretsStore(backing)

        assertTrue(reopened.isUrlTrusted("https://coder.example.com".toURL()))
        assertNull(reopened.apiTokenFor("https://coder.example.com".toURL()))
    }

    @Test
    fun `logout forgets only the matching origin's trusted URL`() {
        secrets.trustDeployment("http://coder.example.com".toURL())
        secrets.trustDeployment("https://coder.example.com".toURL())
        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "token")
        secrets.trustDeployment("https://coder.example.com:8443".toURL())
        values["coder.example.com"] = "legacy-token"

        secrets.clearSessionFor("https://CODER.example.com:443/".toURL())

        val reopened = CoderSecretsStore(backing)
        assertFalse(reopened.isUrlTrusted("https://coder.example.com".toURL()))
        assertNull(reopened.apiTokenFor("https://coder.example.com".toURL()))
        for (url in listOf("http://coder.example.com", "https://coder.example.com:8443")) {
            assertTrue(reopened.isUrlTrusted(url.toURL()), url)
        }
        assertNull(values["coder.example.com"])
    }

    @Test
    fun `deployment trust requires a valid HTTP origin`() {
        assertFalse(secrets.isUrlTrusted("file:///tmp/coder".toURL()))
        assertFailsWith<IllegalArgumentException> {
            secrets.trustDeployment("file:///tmp/coder".toURL())
        }
    }
}
