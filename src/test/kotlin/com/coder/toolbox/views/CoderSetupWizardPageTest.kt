package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.oauth.OAuthTokenResponse
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.pluginTestSettingsStore
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.Credentials
import com.coder.toolbox.views.state.PageRouter
import com.coder.toolbox.views.state.WizardModel
import com.coder.toolbox.views.state.WizardStep
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.Proxy
import java.net.URI
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CoderSetupWizardPageTest {
    private val context = mockk<CoderToolboxContext>(relaxed = true)
    private val url = URI("https://coder.example.test").toURL()
    private val model = WizardModel().apply {
        url = this@CoderSetupWizardPageTest.url
        token = "test-token"
        goTo(WizardStep.CONNECT)
    }
    private val onConnect = mockk<SuspendBiConsumer<CoderRestClient, CoderCLIManager>>(relaxed = true)

    @Test
    fun `saved credentials restore once behind the workspace list without page navigation`() = runTest {
        every { context.cs } returns backgroundScope
        val page = CoderSetupWizardPage.connectStep(
            context, mockk(relaxed = true), url, Credentials.Token("test-token"), onConnect, restoreSavedSession = true,
        )
        val pages = context.envPageManager
        val ui = context.ui

        withMockCli { cli ->
            assertNull(page.pageForDisplay())
            assertNull(page.pageForDisplay())
            runCurrent()

            coVerify(exactly = 1) { cli.login("test-token") }
            coVerify(exactly = 1) { onConnect.accept(any(), cli) }
            verify(exactly = 0) { pages.showPluginEnvironmentsPage(any()) }
            coVerify(exactly = 0) { ui.showUiPageSuspending(any()) }
        }
    }

    @Test
    fun `a failed restore presents the sign-in form and permits a new connection`() = runTest {
        every { context.cs } returns backgroundScope
        val page = CoderSetupWizardPage.connectStep(
            context, mockk(relaxed = true), url, Credentials.Token("test-token"), onConnect, restoreSavedSession = true,
        )
        val ui = context.ui

        withMockCli { cli ->
            coEvery { cli.login("test-token") } throws IllegalStateException("Keychain is locked") andThen "Signed in"
            assertNull(page.pageForDisplay())
            runCurrent()

            assertEquals(WizardStep.URL_REQUEST, page.model.currentStep())
            coVerify(exactly = 1) { ui.showUiPageSuspending(page) }
            coVerify(exactly = 0) { onConnect.accept(any(), any()) }

            page.model.url = url
            page.model.token = "test-token"
            page.model.goTo(WizardStep.CONNECT)
            page.beforeShow()
            runCurrent()
            coVerify(exactly = 2) { cli.login("test-token") }
            coVerify(exactly = 1) { onConnect.accept(any(), cli) }
            page.dispose()
        }
    }

    @Test
    fun `an OAuth credential update failure keeps the signed-in wizard on the workspace list`() = runTest {
        every { context.cs } returns backgroundScope
        val session = CoderOAuthSessionContext(
            clientId = "client-id",
            clientSecret = "client-secret",
            tokenCodeVerifier = "verifier",
            state = "state",
            tokenEndpoint = "$url/oauth/token",
            tokenResponse = OAuthTokenResponse("access-token", "Bearer", null, "refresh-token", null),
            tokenAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
        )
        val router = PageRouter()
        val failure = IllegalStateException("Keychain is locked")
        every { context.secrets.storeOAuthFor(url.toString(), session) } throws failure
        val page = router.getOrCreate {
            CoderSetupWizardPage.connectStep(
                context, mockk(), url, Credentials.OAuth(session),
                onConnect = SuspendBiConsumer { _, _ -> router.finishSignIn(currentCoroutineContext().job) {} },
            )
        }
        val pages = context.envPageManager
        val logger = context.logger
        val log = logger.delegate

        withMockCli {
            context.settingsStore.updatePreferAuthViaOAuth2(true)
            page.beforeShow()
            runCurrent()

            assertEquals(WizardStep.SIGNED_IN, page.model.currentStep())
            verify(exactly = 1) { pages.showPluginEnvironmentsPage(false) }
            verify(exactly = 1) { log.warn(failure, any<String>()) }
            verify(exactly = 0) { logger.showInfoPopup(any(), any()) }
            verify(exactly = 0) { anyConstructed<CoderRestClient>().close() }
            page.dispose()
        }
    }

    @Test
    fun `submitting a token displays the connection step once`() = runTest {
        every { context.cs } returns backgroundScope
        every { context.secrets.apiTokenFor(url) } returns "test-token"
        mockkConstructor(ConnectStep::class)
        try {
            justRun { anyConstructed<ConnectStep>().onVisible() }
            val page = CoderSetupWizardPage.deploymentUrlStep(context, mockk(), mockk())
            page.model.url = url
            page.model.goTo(WizardStep.TOKEN_REQUEST)
            page.beforeShow()
            runCurrent()

            page.actionButtons.value.first().run()
            runCurrent()

            assertEquals(WizardStep.CONNECT, page.model.currentStep())
            verify(exactly = 1) { anyConstructed<ConnectStep>().onVisible() }
            page.dispose()
            page.beforeShow()
            runCurrent()
            verify(exactly = 1) { anyConstructed<ConnectStep>().onVisible() }
        } finally {
            unmockkConstructor(ConnectStep::class)
        }
    }

    @Test
    fun `a second visibility callback during startup logs in to the CLI once`() = runTest {
        lateinit var step: ConnectStep
        val scheduler = StandardTestDispatcher(testScheduler)
        val dispatcher = object : CoroutineDispatcher() {
            private var firstDispatch = true

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (firstDispatch) {
                    firstDispatch = false
                    step.onVisible()
                }
                scheduler.dispatch(context, block)
            }
        }
        every { context.cs } returns CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        step = ConnectStep(context, model, { model.goTo(WizardStep.TOKEN_REQUEST) }, onConnect)
        withMockCli { cli ->
            step.onVisible()
            runCurrent()

            coVerify(exactly = 1) { cli.login("test-token") }
            coVerify(exactly = 1) { onConnect.accept(any(), cli) }
        }
    }

    private fun withMockCli(test: (CoderCLIManager) -> Unit) {
        every { context.settingsStore } returns CoderSettingsStore(
            pluginTestSettingsStore(), Environment(), mockk(relaxed = true),
        )
        every { context.proxySettings.getProxy() } returns Proxy.NO_PROXY
        every { context.proxySettings.getProxyAuth() } returns null
        val cli = mockk<CoderCLIManager>(relaxed = true)
        mockkConstructor(CoderRestClient::class)
        mockkStatic(::ensureCLI)
        try {
            coEvery { anyConstructed<CoderRestClient>().initializeSession() } returns DataGen.user()
            every { anyConstructed<CoderRestClient>().buildVersion } returns "2.39.0"
            coEvery { ensureCLI(context, url, "2.39.0", any()) } returns cli
            test(cli)
        } finally {
            unmockkStatic(::ensureCLI)
            unmockkConstructor(CoderRestClient::class)
        }
    }

    @Test
    fun `a retry waits for a cancelled login and ignores its late error`() = runTest {
        every { context.cs } returns backgroundScope
        val step = ConnectStep(context, model, { model.goTo(WizardStep.TOKEN_REQUEST) }, onConnect)
        val allowCleanup = CompletableDeferred<Unit>()
        withMockCli { cli ->
            coEvery { cli.login("test-token") } coAnswers {
                try {
                    awaitCancellation()
                } catch (ex: CancellationException) {
                    withContext(NonCancellable) { allowCleanup.await() }
                    throw IllegalStateException("Cancelled CLI process exited", ex)
                }
            } andThen "Signed in"
            try {
                step.onVisible()
                runCurrent()
                step.onBack()
                assertEquals(WizardStep.TOKEN_REQUEST, model.currentStep())
                model.goTo(WizardStep.CONNECT)
                step.onVisible()
                runCurrent()
                coVerify(exactly = 1) { cli.login("test-token") }

                allowCleanup.complete(Unit)
                runCurrent()

                assertEquals(WizardStep.CONNECT, model.currentStep())
                coVerify(exactly = 2) { cli.login("test-token") }
                coVerify(exactly = 1) { onConnect.accept(any(), cli) }
            } finally {
                allowCleanup.complete(Unit)
                step.dispose()
            }
        }
    }

    @Test
    fun `a genuine CLI failure returns to token entry and allows a new sign-in`() = runTest {
        every { context.cs } returns backgroundScope
        val step = ConnectStep(context, model, { model.goTo(WizardStep.TOKEN_REQUEST) }, onConnect)
        withMockCli { cli ->
            coEvery { cli.login("test-token") } throws IllegalStateException("Keychain is locked") andThen "Signed in"
            step.onVisible()
            runCurrent()
            assertEquals(WizardStep.TOKEN_REQUEST, model.currentStep())
            coVerify(exactly = 0) { onConnect.accept(any(), any()) }

            model.goTo(WizardStep.CONNECT)
            step.onVisible()
            runCurrent()
            step.dispose()
            step.onVisible()
            runCurrent()

            coVerify(exactly = 2) { cli.login("test-token") }
            coVerify(exactly = 1) { onConnect.accept(any(), cli) }
        }
    }
}
