package com.coder.toolbox.views.state

import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.views.CoderSetupWizardPage
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageRouterTest {

    @Test
    fun `given no active wizard when pending OAuth connection is requested then null is returned`() {
        // given
        val router = PageRouter()

        // when
        val pendingOAuthConnection = router.pendingOAuthConnection

        // then
        assertNull(pendingOAuthConnection)
    }

    @Test
    fun `given active wizard when pending OAuth connection is requested then wizard connection is returned`() {
        // given
        val pendingConnection = PendingOAuthConnection(
            url = URI("https://coder.example.com").toURL(),
            session = oauthSession()
        )
        val wizard = mockk<CoderSetupWizardPage>(relaxed = true)
        every { wizard.pendingOAuthConnection() } returns pendingConnection
        val router = PageRouter()
        router.navigate(wizard)

        // when
        val pendingOAuthConnection = router.pendingOAuthConnection

        // then
        assertEquals(pendingConnection, pendingOAuthConnection)
    }

    private fun oauthSession(): CoderOAuthSessionContext = CoderOAuthSessionContext(
        clientId = "client-id",
        clientSecret = "client-secret",
        tokenCodeVerifier = "verifier",
        state = "state",
        tokenEndpoint = "https://coder.example.com/oauth/token",
        tokenAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC
    )
}
