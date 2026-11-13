package com.coder.toolbox.views.state

import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.views.CoderSetupWizardPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun `a workspace link replaces a wizard being built for auto-connect`() {
        val router = PageRouter()
        val automatic = mockk<CoderSetupWizardPage>(relaxed = true)
        val fromLink = mockk<CoderSetupWizardPage>(relaxUnitFun = true)
        val building = CountDownLatch(1)
        val switching = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val autoConnect = workers.submit {
                router.getOrCreate {
                    building.countDown()
                    check(switching.await(5, TimeUnit.SECONDS))
                    automatic
                }
            }
            assertTrue(building.await(5, TimeUnit.SECONDS))
            val openLink = workers.submit {
                switching.countDown()
                router.navigate(fromLink)
            }
            autoConnect.get(5, TimeUnit.SECONDS)
            openLink.get(5, TimeUnit.SECONDS)

            assertSame(fromLink, router.activePage)
            verify(exactly = 1) { automatic.dispose() }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `a cancelled sign-in cannot publish a session or dismiss its replacement`() {
        val router = PageRouter()
        val replacement = mockk<CoderSetupWizardPage>(relaxUnitFun = true)
        router.navigate(replacement)
        val cancelledConnection = Job().apply { cancel() }

        assertFailsWith<CancellationException> {
            router.finishSignIn(cancelledConnection) { error("The cancelled session was started") }
        }

        assertSame(replacement, router.activePage)
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
