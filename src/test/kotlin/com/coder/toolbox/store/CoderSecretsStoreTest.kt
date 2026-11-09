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
    fun `hostname-only credentials are ignored and preserved`() {
        values["coder.example.com"] = "old-token"
        values["last-deployment-url"] = "https://coder.example.com"

        assertNull(secrets.apiTokenFor("https://coder.example.com".toURL()))
        assertNull(secrets.apiTokenFor("http://coder.example.com".toURL()))
        assertFalse(secrets.isUrlTrusted("https://coder.example.com".toURL()))

        secrets.storeApiTokenFor("https://coder.example.com".toURL(), "new-token")
        assertEquals("new-token", secrets.apiTokenFor("https://coder.example.com".toURL()))
        assertEquals("old-token", values["coder.example.com"])

        secrets.clearSessionFor("https://coder.example.com".toURL())
        assertNull(secrets.apiTokenFor("https://coder.example.com".toURL()))
        assertEquals("old-token", values["coder.example.com"])
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
    fun `equivalent root URLs find and clear the OAuth session saved at sign-in`() {
        val signedInAt = "https://CODER.example.com:443/"
        secrets.trustDeployment(signedInAt.toURL())
        val session = StoredOAuthSession(
            clientId = "client-id",
            clientSecret = "client-secret",
            refreshToken = "refresh-token",
            tokenAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
            tokenEndpoint = "https://coder.example.com/oauth/token",
        )
        secrets.storeOAuthFor(signedInAt, session.toSessionContext())

        val reopened = CoderSecretsStore(backing)
        assertEquals(session, reopened.oauthSessionFor("https://coder.example.com"))
        assertNull(reopened.oauthSessionFor("https://other.example.com"))
        assertNull(reopened.oauthSessionFor("https://coder.example.com/other/"))

        reopened.trustDeployment("https://coder.example.com".toURL())
        val refreshed = session.copy(refreshToken = "refreshed-token")
        reopened.storeOAuthFor("https://coder.example.com", refreshed.toSessionContext())
        assertEquals(refreshed, reopened.oauthSessionFor(signedInAt))

        reopened.clearSessionFor("https://coder.example.com".toURL())
        assertNull(reopened.oauthSessionFor(signedInAt))
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
        assertEquals("legacy-token", values["coder.example.com"])
    }

    @Test
    fun `deployment trust requires a valid HTTP origin`() {
        assertFalse(secrets.isUrlTrusted("file:///tmp/coder".toURL()))
        assertFailsWith<IllegalArgumentException> {
            secrets.trustDeployment("file:///tmp/coder".toURL())
        }
    }
}
