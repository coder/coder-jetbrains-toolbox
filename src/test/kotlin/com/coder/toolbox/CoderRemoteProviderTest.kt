package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.ensureCLI
import com.coder.toolbox.diagnostics.CoderLogger
import com.coder.toolbox.oauth.OAuth2Client
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.sdk.v2.models.InvalidCoderIdentifierException
import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.session.SessionId
import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.CoderProtocolHandler
import com.coder.toolbox.util.InMemoryPluginSecretStore
import com.coder.toolbox.util.pluginTestSettingsStore
import com.coder.toolbox.views.CoderSetupWizardPage
import com.coder.toolbox.views.SuspendBiConsumer
import com.coder.toolbox.views.state.PageRouter
import com.coder.toolbox.views.state.StoredOAuthSession
import com.coder.toolbox.views.state.WizardStep
import com.coder.toolbox.views.state.toSessionContext
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.ToolboxUi
import com.sun.net.httpserver.HttpServer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.FileNotFoundException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CoderRemoteProviderTest {

    private lateinit var mockClient: CoderRestClient
    private lateinit var mockCli: CoderCLIManager
    private lateinit var mockContext: CoderToolboxContext
    private lateinit var mockUi: ToolboxUi
    private lateinit var mockLogger: CoderLogger
    private lateinit var underlyingLogger: Logger
    private lateinit var remoteProvider: CoderRemoteProvider

    @BeforeTest
    fun setup() {
        mockClient = mockk(relaxed = true)
        mockCli = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)
        mockUi = mockk(relaxed = true)
        every { mockContext.ui } returns mockUi
        underlyingLogger = mockk(relaxed = true)
        mockLogger = CoderLogger(
            underlyingLogger,
            mockk<ToolboxUi>(relaxed = true),
            CoroutineScope(Dispatchers.Unconfined),
            mockk<LocalizableStringFactory>(relaxed = true),
            mockk(relaxed = true),
        )
        val settingsStore = mockk<CoderSettingsStore>(relaxed = true)
        every { mockContext.settingsStore } returns settingsStore
        every { mockContext.logger } returns mockLogger
        every { mockContext.secrets.isUrlTrusted(any()) } returns false
        every { mockClient.url } returns URI("https://coder.example.com").toURL()
        every { mockClient.me } returns DataGen.user()
        every { mockCli.deploymentURL } returns URI("https://coder.example.com").toURL()
        remoteProvider = CoderRemoteProvider(mockContext)
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `explicit logout clears saved credentials and CLI session`() = runTest {
        every { mockCli.usesTokenAuth } returns true
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)

        remoteProvider.logout()

        verify { mockContext.secrets.clearSessionFor(match { it.toString() == "https://coder.example.com" }) }
        coVerify(exactly = 1) { mockCli.logout() }
        assertFalse(remoteProvider.getAccountDropDown().visibility.value)
    }

    @Test
    fun `CLI logout failure still clears Toolbox session and warns`() = runTest {
        every { mockCli.usesTokenAuth } returns true
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)
        coEvery { mockCli.logout() } throws IllegalStateException("keyring unavailable")

        remoteProvider.logout()

        verify { mockContext.secrets.clearSessionFor(match { it.toString() == "https://coder.example.com" }) }
        verify { underlyingLogger.warn(any<Throwable>(), match<String> { it.contains("CLI credential may remain") }) }
        assertFalse(remoteProvider.getAccountDropDown().visibility.value)
    }

    @CsvSource(
        "https://coder.example.com, false, true",
        "https://other.example.com, false, true",
        "https://other.example.com, true, false",
    )
    @ParameterizedTest(name = "After logout, link to {0}, B remembered={1}, deployment prompt={2}")
    fun `logout forgets A while each subsequent link uses its own deployment history`(
        target: String,
        rememberB: Boolean,
        expectPrompt: Boolean,
    ) = runTest {
        val a = mockClient.url
        val b = URI("https://other.example.com").toURL()
        val secrets = CoderSecretsStore(InMemoryPluginSecretStore())
        every { mockContext.secrets } returns secrets
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns false
        secrets.storeApiTokenFor(a, "saved-a")
        secrets.trustDeployment(a)
        if (rememberB) {
            secrets.storeApiTokenFor(b, "saved-b")
            secrets.trustDeployment(b)
        }
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        setPrivateField(provider, "client", mockClient)
        setPrivateField(provider, "cli", mockCli)
        val user = DataGen.user()
        coEvery { provider["userForToken"](b, "saved-b") } returns user
        coEvery { provider["userForToken"](b, "link-token") } returns user
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false

        provider.logout()
        provider.handleUri(workspaceLink(target))

        assertNull(secrets.apiTokenFor(a))
        assertFalse(secrets.isUrlTrusted(a))
        assertEquals(rememberB, secrets.isUrlTrusted(b))
        coVerify(exactly = if (expectPrompt) 1 else 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        coVerify(exactly = if (expectPrompt) 0 else 1) { mockUi.showUiPageSuspending(any()) }
    }

    @Test
    fun `provider shutdown preserves credentials`() {
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)

        remoteProvider.close()

        coVerify(exactly = 0) { mockCli.logout() }
        verify(exactly = 0) { mockContext.secrets.clearSessionFor(any()) }
    }

    @Test
    fun `certificate logout does not remove a shared token session`() = runTest {
        every { mockCli.usesTokenAuth } returns false
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)

        remoteProvider.logout()

        coVerify(exactly = 0) { mockCli.logout() }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restoring an OAuth session logs in to the CLI once and saves the rotated token`() = runTest {
        val url = mockCli.deploymentURL
        val stored = storedOAuthSession()
        val session = stored.toSessionContext()
        val refreshed = session.tokenResponse!!.copy(accessToken = "new-access-token", refreshToken = "new-refresh-token")
        val secrets = mockContext.secrets
        every { secrets.oauthSessionFor(url.toString()) } returns stored
        every { mockContext.deploymentUrl } returns url
        every { mockContext.cs } returns backgroundScope
        every { mockContext.settingsStore } returns CoderSettingsStore(
            pluginTestSettingsStore(), Environment(), underlyingLogger,
        ).apply { updatePreferAuthViaOAuth2(true) }
        every { mockContext.proxySettings.getProxy() } returns Proxy.NO_PROXY
        every { mockContext.proxySettings.getProxyAuth() } returns null

        mockkConstructor(OAuth2Client::class, CoderRestClient::class)
        mockkStatic(::ensureCLI)
        try {
            coEvery { anyConstructed<OAuth2Client>().refreshToken(session) } returns refreshed
            val user = DataGen.user()
            coEvery { anyConstructed<CoderRestClient>().initializeSession() } returns user
            every { anyConstructed<CoderRestClient>().me } returns user
            every { anyConstructed<CoderRestClient>().buildVersion } returns "2.39.0"
            coEvery { anyConstructed<CoderRestClient>().templates() } returns emptyList()
            coEvery { anyConstructed<CoderRestClient>().workspaces(any()) } returns emptyList()
            coEvery { ensureCLI(mockContext, url, "2.39.0", any()) } returns mockCli

            assertNull(remoteProvider.getOverrideUiPage())
            runCurrent()

            coVerify(exactly = 1) { mockCli.login(refreshed.accessToken) }
            verify(exactly = 1) { secrets.storeOAuthFor(url.toString(), session.copy(tokenResponse = refreshed)) }
        } finally {
            remoteProvider.close()
            unmockkStatic(::ensureCLI)
            unmockkConstructor(OAuth2Client::class, CoderRestClient::class)
        }
    }

    @Test
    fun `OAuth refresh updates the CLI session for the active origin`() = runTest {
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)
        val url = URI("https://CODER.example.com:443/").toURL()
        val stored = storedOAuthSession().toSessionContext()
        val refreshed = stored.copy(tokenResponse = stored.tokenResponse!!.copy(accessToken = "refreshed-token"))
        val secrets = mockContext.secrets

        remoteProvider.onTokenRefreshed(url, refreshed)

        coVerify(exactly = 1) { mockCli.login("refreshed-token") }
        verify(exactly = 1) { secrets.storeOAuthFor(url.toString(), refreshed) }
    }

    @Test
    fun `late OAuth refresh never sends the token to another active origin`() = runTest {
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)
        val stored = storedOAuthSession().toSessionContext()
        val refreshed = stored.copy(tokenResponse = stored.tokenResponse!!.copy(accessToken = "refreshed-token"))
        val secrets = mockContext.secrets

        for (deployment in listOf(
            "http://coder.example.com",
            "https://coder.example.com:8443",
            "https://other.example.com",
        )) {
            remoteProvider.onTokenRefreshed(URI(deployment).toURL(), refreshed)
            verify(exactly = 1) { secrets.storeOAuthFor(deployment, refreshed) }
        }

        coVerify(exactly = 0) { mockCli.login(any()) }
    }

    @Test
    fun `OAuth refresh after disconnection only saves credentials for its deployment`() = runTest {
        setPrivateField(remoteProvider, "cli", mockCli)
        val url = URI("https://coder.example.com").toURL()
        val stored = storedOAuthSession().toSessionContext()
        val refreshed = stored.copy(tokenResponse = stored.tokenResponse!!.copy(accessToken = "refreshed-token"))
        val secrets = mockContext.secrets

        remoteProvider.onTokenRefreshed(url, refreshed)

        coVerify(exactly = 0) { mockCli.login(any()) }
        verify(exactly = 1) { secrets.storeOAuthFor(url.toString(), refreshed) }
    }

    @Test
    fun `OAuth refresh checks the CLI origin while a connection is being replaced`() = runTest {
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)
        val url = URI("https://other.example.com").toURL()
        every { mockClient.url } returns url
        val stored = storedOAuthSession().toSessionContext()
        val refreshed = stored.copy(tokenResponse = stored.tokenResponse!!.copy(accessToken = "refreshed-token"))

        remoteProvider.onTokenRefreshed(url, refreshed)

        coVerify(exactly = 0) { mockCli.login(any()) }
    }

    @Test
    fun `given an empty workspace list expect an empty list of environments`() = runTest {
        // given
        coEvery { mockClient.workspaces(any()) } returns emptyList()
        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)
        // then
        assertTrue(result.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `SSH configuration failure does not prevent resolved workspaces from being published`() = runTest {
        // given
        every { mockContext.cs } returns CoroutineScope(StandardTestDispatcher(testScheduler))
        every { mockContext.settingsStore.sshConfigPath } returns "/Users/test/.ssh/config"
        val agent = mockAgent("agent1")
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(mockResource(listOf(agent))))
        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)
        val failure = FileNotFoundException("Permission denied")
        every { mockCli.configSsh(any(), any(), any()) } throws failure

        // when
        val pollJob = remoteProvider.poll(mockClient, mockCli)
        runCurrent()

        // then
        val environments = remoteProvider.environments.value
        when (environments) {
            is LoadableState.Value -> assertEquals("ws1.agent1", environments.value.single().id)
            else -> error("Expected resolved workspaces to be published, but got $environments")
        }
        val warningText = slot<String>()
        verify(exactly = 1) {
            underlyingLogger.warn(failure, capture(warningText))
        }
        assertTrue(warningText.captured.contains("Permission denied"))

        pollJob.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `workspace change refreshes sessions after the workspace request`() = runTest {
        every { mockContext.cs } returns CoroutineScope(StandardTestDispatcher(testScheduler))
        val firstSessionId = SessionId.generate()
        val secondSessionId = SessionId.generate()
        var sessionsStarted = false
        val firstEnvironment = mockk<CoderRemoteEnvironment>(relaxed = true) {
            every { id } returns "ws1.agent1"
            every { currentSessionId() } answers { firstSessionId.takeIf { sessionsStarted } }
        }
        val secondEnvironment = mockk<CoderRemoteEnvironment>(relaxed = true) {
            every { id } returns "ws1.agent2"
            every { currentSessionId() } answers { secondSessionId.takeIf { sessionsStarted } }
        }
        remoteProvider.lastEnvironments.addAll(listOf(secondEnvironment, firstEnvironment))
        val workspace = mockWorkspace(
            "ws1",
            WorkspaceStatus.RUNNING,
            listOf(mockResource(listOf(mockAgent("agent1"), mockAgent("agent2")))),
        )
        coEvery { mockClient.workspaces(any()) } answers {
            sessionsStarted = true
            listOf(workspace)
        }

        val pollJob = remoteProvider.poll(mockClient, mockCli)
        runCurrent()

        verify(exactly = 1) {
            underlyingLogger.info(match<String> {
                it.startsWith("client_session_id=$firstSessionId Workspaces have changed, reconfiguring CLI:")
            })
        }
        verify(exactly = 1) {
            underlyingLogger.info(match<String> {
                it.startsWith("client_session_id=$secondSessionId Workspaces have changed, reconfiguring CLI:")
            })
        }

        pollJob.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `workspace poll failure is emitted for every current Toolbox SSH session`() = runTest {
        every { mockContext.cs } returns CoroutineScope(StandardTestDispatcher(testScheduler))
        val firstSessionId = SessionId.generate()
        val secondSessionId = SessionId.generate()
        remoteProvider.lastEnvironments.addAll(
            listOf(
                mockk<CoderRemoteEnvironment>(relaxed = true) {
                    every { currentSessionId() } returns firstSessionId
                },
                mockk<CoderRemoteEnvironment>(relaxed = true) {
                    every { currentSessionId() } returns secondSessionId
                },
            )
        )
        val failure = IllegalStateException("poll failed")
        coEvery { mockClient.workspaces(any()) } throws failure

        val pollJob = remoteProvider.poll(mockClient, mockCli)
        runCurrent()

        verify(exactly = 1) {
            underlyingLogger.error(failure, "client_session_id=$firstSessionId workspace polling error encountered")
        }
        verify(exactly = 1) {
            underlyingLogger.error(failure, "client_session_id=$secondSessionId workspace polling error encountered")
        }

        pollJob.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `removed environment session is retained for its final shared configuration logs`() = runTest {
        every { mockContext.cs } returns CoroutineScope(StandardTestDispatcher(testScheduler))
        val sessionId = SessionId.generate()
        var disposed = false
        val existingEnvironment = mockk<CoderRemoteEnvironment>(relaxed = true) {
            every { id } returns "ws1.agent1"
            every { currentSessionId() } answers { sessionId.takeUnless { disposed } }
            every { dispose() } answers { disposed = true }
        }
        remoteProvider.lastEnvironments.add(existingEnvironment)
        val stoppedWorkspace = mockWorkspace("ws1", WorkspaceStatus.STOPPED, emptyList())
        coEvery { mockClient.workspaces(any()) } returns listOf(stoppedWorkspace)

        val pollJob = remoteProvider.poll(mockClient, mockCli)
        runCurrent()

        verify(exactly = 1) { existingEnvironment.dispose() }
        verify(exactly = 1) {
            underlyingLogger.info(match<String> {
                it.startsWith("client_session_id=$sessionId Workspaces have changed, reconfiguring CLI:")
            })
        }
        verify(exactly = 1) {
            mockCli.configSsh(any(), setOf(sessionId), any())
        }

        pollJob.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `identifier failures from SSH rendering are not treated as writable config errors`() = runTest {
        every { mockContext.cs } returns CoroutineScope(StandardTestDispatcher(testScheduler))
        val agent = mockAgent("agent1")
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(mockResource(listOf(agent))))
        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)
        every { mockCli.configSsh(any(), any(), any()) } throws
                InvalidCoderIdentifierException("The deployment returned an invalid workspace name")

        val pollJob = remoteProvider.poll(mockClient, mockCli)
        runCurrent()

        assertTrue(remoteProvider.environments.value is LoadableState.Loading)
        verify(exactly = 0) {
            underlyingLogger.warn(any<Throwable>(), match<String> { it.startsWith("Workspaces remain available") })
        }

        pollJob.cancel()
    }

    @Test
    fun `workspace resolution passes the default owner filter query`() = runTest {
        coEvery { mockClient.workspaces("owner:me") } returns emptyList()

        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        assertTrue(result.isEmpty())
        coVerify(exactly = 1) { mockClient.workspaces("owner:me") }
    }

    @Test
    fun `given a running workspace with two agents then two environments are returned`() = runTest {
        // given
        val agent1 = mockAgent("agent1")
        val agent2 = mockAgent("agent2")
        val resource = mockResource(agents = listOf(agent1, agent2))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(2, result.size)
        assertEquals("ws1.agent1", result[0].id)
        assertEquals("ws1.agent2", result[1].id)
    }

    @Test
    fun `given a stopped workspace then a workspace only environment is returned`() = runTest {
        // given
        val workspace = mockWorkspace("ws1", WorkspaceStatus.STOPPED, emptyList())

        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        assertEquals("ws1", result[0].id)
        assertNull(result[0].toWorkspaceAddressOrNull())
    }

    @Test
    fun `given a pending workspace then a workspace only environment is returned`() = runTest {
        // given
        val workspace = mockWorkspace("ws1", WorkspaceStatus.PENDING, emptyList())

        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        assertEquals("ws1", result[0].id)
    }

    @Test
    fun `given a running workspace with empty resources then no environment is returned`() = runTest {
        // given
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, emptyList())

        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `given a running workspace with a resource that has no agents (ie null) then no environment is returned`() =
        runTest {
            // given
            val resource = mockResource(agents = null)
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

            coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `given a running workspace with a resource that has an empty list of agents then no environment is returned`() =
        runTest {
            // given
            val resource = mockResource(agents = emptyList())
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

            coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `given a running workspace with a resource that has two two agents with same name but different ids then returns only one environment is resolved`() =
        runTest {
            // given
            val agent1 = mockAgent("agent1")
            val agent2 = mockAgent("agent1") // Same name, different ID
            val resource = mockResource(agents = listOf(agent1, agent2))
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

            coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(1, result.size)
            assertEquals("ws1.agent1", result[0].id)
        }

    @Test
    fun `given a running workspace with two resources each one with an agent that has the same name but different ids then returns only one environment is resolved`() =
        runTest {
            // given
            val agent1 = mockAgent("agent1")
            val agent2 = mockAgent("agent1")
            val resource1 = mockResource(agents = listOf(agent1))
            val resource2 = mockResource(agents = listOf(agent2))
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource1, resource2))

            coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(1, result.size)
            assertEquals("ws1.agent1", result[0].id)
        }

    @Test
    fun `given an existing environment then it is updated instead of creating a new one`() = runTest {
        // given
        val agent = mockAgent("agent1")
        val resource = mockResource(agents = listOf(agent))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

        val existingEnv = mockk<CoderRemoteEnvironment>(relaxed = true)
        remoteProvider.lastEnvironments.add(existingEnv)

        every { existingEnv.id } returns "ws1.agent1"
        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        assertSame(existingEnv, result[0])
        verify(exactly = 1) { existingEnv.update(workspace, agent) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `slow build logs do not delay resolving other workspaces`() = runTest {
        every { mockContext.cs } returns backgroundScope
        val workspaces = listOf("slow", "fast").map { name ->
            DataGen.workspace(name).let {
                it.copy(latestBuild = it.latestBuild.copy(status = WorkspaceStatus.STARTING))
            }
        }
        val slowBuildID = workspaces[0].latestBuild.id
        val fastBuildID = workspaces[1].latestBuild.id
        val slowResponse = CompletableDeferred<List<ProvisionerJobLog>>()
        coEvery { mockClient.workspaces(any()) } returns workspaces
        coEvery { mockClient.workspaceBuildLogs(slowBuildID) } coAnswers { slowResponse.await() }
        coEvery { mockClient.workspaceBuildLogs(fastBuildID) } returns emptyList()
        val environments = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)
        remoteProvider.lastEnvironments.addAll(environments)
        try {
            val refresh = async { remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli) }
            runCurrent()
            assertTrue(refresh.isCompleted)
            assertEquals(listOf("fast", "slow"), refresh.await().map { it.id })
            assertFalse(slowResponse.isCompleted)
            coVerify(exactly = 1) { mockClient.workspaceBuildLogs(slowBuildID) }
            coVerify(exactly = 1) { mockClient.workspaceBuildLogs(fastBuildID) }

            val nextRefresh = async { remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli) }
            runCurrent()
            assertTrue(nextRefresh.isCompleted)
            coVerify(exactly = 1) { mockClient.workspaceBuildLogs(slowBuildID) }
            coVerify(exactly = 2) { mockClient.workspaceBuildLogs(fastBuildID) }
        } finally {
            environments.forEach { it.dispose() }
        }
    }

    @Test
    fun `an empty query still routes OAuth callbacks to the OAuth handler`() = runTest {
        remoteProvider.handleUri(URI("jetbrains://gateway/com.coder.toolbox"))
        verify { underlyingLogger.info("No query parameters were provided") }

        remoteProvider.handleUri(URI("jetbrains://gateway/com.coder.toolbox/auth"))
        verify { underlyingLogger.error("OAuth2 callback arrived but the setup wizard is no longer active") }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @ValueSource(
        strings = [
            "https://coder2.example.com/",
            "https://coder2.example.com/dashboard?view=recent#workspaces",
        ]
    )
    @ParameterizedTest
    fun `accepted cold start link presents the new deployment while the old session restores`(target: String) =
        runTest {
            every { mockContext.cs } returns backgroundScope
            val previousUrl = URI("https://coder.example.com").toURL()
            every { mockContext.deploymentUrl } returns previousUrl
            every { mockContext.secrets.apiTokenFor(previousUrl) } returns "saved-token"
            every { mockContext.settingsStore.requiresMTlsAuth } returns false
            every { mockContext.settingsStore.requiresTokenAuth } returns true
            coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns true
            assertNull(remoteProvider.getOverrideUiPage())
            val previousWizard = setupWizard(remoteProvider)
            assertEquals(previousUrl, previousWizard.model.url)
            assertEquals(WizardStep.RESTORE_SESSION, previousWizard.model.currentStep())
            val displayedWizard = slot<CoderSetupWizardPage>()
            coEvery { mockUi.showUiPageSuspending(capture(displayedWizard)) } returns Unit

            remoteProvider.handleUri(workspaceLink(target))

            // Selecting an already visible provider can leave Toolbox on the old page.
            // No second visibility callback or request for the override page is simulated.
            coVerify(exactly = 1) { mockUi.showUiPageSuspending(any()) }
            val wizard = displayedWizard.captured
            assertEquals(WizardStep.CONNECT, wizard.model.currentStep())
            assertEquals("https://coder2.example.com", wizard.model.url.toString())
            assertEquals("link-token", wizard.model.token)
            assertSame(wizard, remoteProvider.getOverrideUiPage())
            coVerify(exactly = 1) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        }

    private fun setupWizard(provider: CoderRemoteProvider): CoderSetupWizardPage {
        val router =
            provider.javaClass.getDeclaredField("router").apply { isAccessible = true }.get(provider) as PageRouter
        return requireNotNull(router.activePage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a cold link signs in to B and hands off its workspace without a focus change`() = runTest {
        val a = URI("https://a.example.com").toURL()
        val b = URI("https://b.example.com").toURL()
        val secrets = mockContext.secrets
        val environmentsPage = mockContext.envPageManager
        every { mockContext.settingsStore } returns CoderSettingsStore(
            pluginTestSettingsStore(), Environment(), underlyingLogger,
        )
        every { mockContext.cs } returns backgroundScope
        every { mockContext.proxySettings.getProxy() } returns Proxy.NO_PROXY
        every { mockContext.proxySettings.getProxyAuth() } returns null
        every { mockContext.deploymentUrl } returns a
        every { secrets.apiTokenFor(a) } returns "saved-a"
        val handler = mockk<CoderProtocolHandler>(relaxed = true)
        setPrivateField(remoteProvider, "linkHandler", handler)
        val user = DataGen.user()
        val aStarted = CompletableDeferred<Unit>()
        val aCancelled = CompletableDeferred<Unit>()
        val shown = slot<CoderSetupWizardPage>()
        mockkConstructor(CoderRestClient::class)
        mockkStatic(::ensureCLI)
        try {
            coEvery { anyConstructed<CoderRestClient>().initializeSession() } coAnswers {
                aStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    aCancelled.complete(Unit)
                }
            } andThen user
            every { anyConstructed<CoderRestClient>().me } returns user
            every { anyConstructed<CoderRestClient>().buildVersion } returns "2.39.0"
            coEvery { anyConstructed<CoderRestClient>().workspaces(any()) } returns emptyList()
            coEvery { ensureCLI(mockContext, b, "2.39.0", any()) } returns mockCli
            coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns true
            coEvery { mockUi.showUiPageSuspending(capture(shown)) } coAnswers {
                shown.captured.beforeShow()
            }
            every { environmentsPage.showPluginEnvironmentsPage(false) } answers {
                assertNull(remoteProvider.getOverrideUiPage())
                shown.captured.afterHide()
            }
            assertNull(remoteProvider.getOverrideUiPage())
            runCurrent()
            assertTrue(aStarted.isCompleted)
            remoteProvider.handleUri(workspaceLink(b.toString()))
            runCurrent()

            assertTrue(aCancelled.isCompleted)
            verify(exactly = 1) { anyConstructed<CoderRestClient>().close() }
            coVerify(exactly = 1) { mockCli.login("link-token") }
            verify(exactly = 1) { secrets.storeApiTokenFor(b, "link-token") }
            coVerify(exactly = 1) { handler.handle(any(), b, any(), mockCli) }
            verify(exactly = 1) { environmentsPage.showPluginEnvironmentsPage(false) }
            assertNull(remoteProvider.getOverrideUiPage())
        } finally {
            remoteProvider.close()
            unmockkStatic(::ensureCLI)
            unmockkConstructor(CoderRestClient::class)
        }
    }

    @Test
    fun `links to the active origin continue without confirmation and preserve its URL`() = runTest {
        val secrets = mockContext.secrets
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        val activeUrl = URI("https://coder.example.com").toURL()
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        val handler = mockk<CoderProtocolHandler>(relaxed = true)
        setPrivateField(provider, "client", mockClient)
        setPrivateField(provider, "cli", mockCli)
        setPrivateField(provider, "linkHandler", handler)
        coEvery { provider["userForToken"](activeUrl, "link-token") } returns mockClient.me
        coEvery { provider["refreshSession"](activeUrl, "link-token") } returns (mockClient to mockCli)

        val targets = listOf(
            "https://coder.example.com",
            "https://CODER.example.com:443/",
            "https://coder.example.com/different/path/",
        )
        for (target in targets) {
            provider.handleUri(workspaceLink(target))
        }

        coVerify(exactly = 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        coVerify(exactly = targets.size) { handler.handle(any(), activeUrl, mockClient, mockCli) }
        verify(exactly = 0) { secrets.isUrlTrusted(any()) }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
        verify(exactly = 0) { underlyingLogger.info(match<String> { it.contains("link-token") }) }
        assertNull(provider.getOverrideUiPage())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `different origins wait for confirmation and cancellation preserves the active session`() = runTest {
        val settings = mockContext.settingsStore
        val secrets = mockContext.secrets
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)

        for (target in listOf(
            "https://other.example.com",
            "http://coder.example.com",
            "https://coder.example.com:8443",
        )) {
            val decision = CompletableDeferred<Boolean>()
            coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } coAnswers { decision.await() }
            val handling = async { remoteProvider.handleUri(workspaceLink(target)) }
            runCurrent()

            assertFalse(handling.isCompleted)
            assertNull(remoteProvider.getOverrideUiPage())
            verify(exactly = 0) { mockContext.popupPluginMainPage() }
            coVerify(exactly = 0) { mockClient.initializeSession() }
            coVerify(exactly = 0) { mockCli.login(any()) }

            decision.complete(false)
            handling.await()
            assertNull(remoteProvider.getOverrideUiPage())
        }
        coVerify(exactly = 3) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        verify(exactly = 0) { secrets.storeApiTokenFor(any(), any()) }
        verify(exactly = 0) { settings.updateLastUsedUrl(any()) }
    }

    @CsvSource("saved-token, 1", "link-token, 0")
    @ParameterizedTest(name = "Reopen a known deployment with {0} saved")
    fun `known deployment reopens at its origin without confirmation for the same account`(
        savedToken: String,
        savedTokenLookups: Int,
    ) = runTest {
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.secrets.isUrlTrusted(url) } returns true
        every { mockContext.secrets.apiTokenFor(url) } returns savedToken
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        val user = DataGen.user()
        coEvery { provider["userForToken"](url, "link-token") } returns user
        coEvery { provider["userForToken"](url, "saved-token") } returns user

        provider.handleUri(workspaceLink("https://CODER.example.com:443/different/path"))

        val page = provider.getOverrideUiPage() as CoderSetupWizardPage
        assertEquals(WizardStep.CONNECT, page.model.currentStep())
        assertEquals(url.toString(), page.model.url.toString())
        assertEquals("link-token", page.model.token)
        coVerify(exactly = 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        coVerify(exactly = 1) { provider["userForToken"](url, "link-token") }
        coVerify(exactly = savedTokenLookups) { provider["userForToken"](url, "saved-token") }
        verify(exactly = 1) { mockContext.popupPluginMainPage() }
    }

    @Test
    fun `link to another known deployment compares that deployment's saved account`() = runTest {
        val target = URI("https://other.example.com").toURL()
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockClient.me } returns DataGen.user().copy(username = "active-user")
        every { mockContext.secrets.isUrlTrusted(target) } returns true
        every { mockContext.secrets.apiTokenFor(target) } returns "saved-token"
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        setPrivateField(provider, "client", mockClient)
        setPrivateField(provider, "cli", mockCli)
        val savedUser = DataGen.user().copy(username = "saved-user")
        coEvery { provider["userForToken"](target, "link-token") } returns savedUser
        coEvery { provider["userForToken"](target, "saved-token") } returns savedUser

        provider.handleUri(workspaceLink("https://other.example.com"))

        val page = provider.getOverrideUiPage() as CoderSetupWizardPage
        assertEquals(target.toString(), page.model.url.toString())
        assertEquals("link-token", page.model.token)
        coVerify(exactly = 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        verify(exactly = 0) { mockClient.close() }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `account change waits for confirmation before modifying the active session`() = runTest {
        val url = URI("https://coder.example.com").toURL()
        val secrets = mockContext.secrets
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        setPrivateField(provider, "client", mockClient)
        setPrivateField(provider, "cli", mockCli)
        coEvery { provider["userForToken"](url, "link-token") } returns DataGen.user().copy(username = "other-user")
        val decision = CompletableDeferred<Boolean>()
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } coAnswers { decision.await() }

        val handling = async { provider.handleUri(workspaceLink("https://coder.example.com")) }
        runCurrent()

        assertFalse(handling.isCompleted)
        assertNull(provider.getOverrideUiPage())
        verify(exactly = 0) { mockClient.close() }
        coVerify(exactly = 0) { mockCli.login(any()) }
        verify {
            mockContext.i18n.pnotr(match {
                it.contains("other-user") && it.contains("tester") && !it.contains("link-token")
            })
        }

        decision.complete(false)
        handling.await()

        coVerify(exactly = 0) { provider["refreshSession"](any<URL>(), any<String>()) }
        verify(exactly = 0) { secrets.storeApiTokenFor(any(), any()) }
        verify(exactly = 0) { secrets.trustDeployment(any()) }
        assertNull(provider.getOverrideUiPage())
    }

    @Test
    fun `accepting an account change uses the link token`() = runTest {
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.secrets.isUrlTrusted(url) } returns true
        every { mockContext.secrets.apiTokenFor(url) } returns "saved-token"
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns true
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        // Separate accounts can have the same username after a name is reused.
        coEvery { provider["userForToken"](url, "link-token") } returns DataGen.user()
        coEvery { provider["userForToken"](url, "saved-token") } returns DataGen.user()

        provider.handleUri(workspaceLink("https://coder.example.com"))

        val page = provider.getOverrideUiPage() as CoderSetupWizardPage
        assertEquals("link-token", page.model.token)
        coVerify(exactly = 1) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
    }

    @NullSource
    @ValueSource(strings = ["expired-token"])
    @ParameterizedTest(name = "Confirm sign-in when the saved token is {0}")
    fun `a missing or expired saved token requires sign-in confirmation`(savedToken: String?) = runTest {
        val url = URI("https://coder.example.com").toURL()
        val i18n = mockContext.i18n
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.secrets.isUrlTrusted(url) } returns true
        every { mockContext.secrets.apiTokenFor(url) } returns savedToken
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        coEvery { provider["userForToken"](url, "link-token") } returns DataGen.user()
        coEvery { provider["userForToken"](url, "expired-token") } returns null

        provider.handleUri(workspaceLink("https://coder.example.com"))

        verify { i18n.ptrl("Sign in with the token from the link?") }
        verify(exactly = 0) { i18n.ptrl("Connect to a Coder deployment?") }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @Test
    fun `declining an account change preserves an in-flight auto-connect wizard`() = runTest {
        every { mockContext.cs } returns backgroundScope
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns false
        every { mockContext.secrets.apiTokenFor(url) } returns "saved-token"
        every { mockContext.secrets.isUrlTrusted(url) } returns true
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        coEvery { provider["userForToken"](url, "link-token") } returns DataGen.user().copy(username = "other-user")
        coEvery { provider["userForToken"](url, "saved-token") } returns DataGen.user().copy(username = "saved-user")
        assertNull(provider.getOverrideUiPage())
        val existingWizard = setupWizard(provider)

        provider.handleUri(workspaceLink("https://coder.example.com"))

        assertNull(provider.getOverrideUiPage())
        assertSame(existingWizard, setupWizard(provider))
        assertEquals(WizardStep.RESTORE_SESSION, existingWizard.model.currentStep())
        assertEquals("saved-token", existingWizard.model.token)
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @CsvSource(
        "tester, 200, false, true",
        "other-user, 200, true, false",
        "tester, 401, false, false",
    )
    @ParameterizedTest(name = "Link token for {0} with HTTP {1}: prompt={2}, refresh={3}")
    fun `link tokens are validated before changing the active session`(
        username: String,
        responseCode: Int,
        expectsPrompt: Boolean,
        expectsRefresh: Boolean,
    ) = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = URI("http://127.0.0.1:${server.address.port}").toURL()
        val requests = ConcurrentLinkedQueue<String>()
        val userId = if (username == mockClient.me.username) mockClient.me.id else UUID.randomUUID()
        server.createContext("/api/v2/users/me") { exchange ->
            requests.add(exchange.requestHeaders.getFirst("Coder-Session-Token"))
            val body = if (responseCode == 200) {
                """{"id":"$userId","username":"$username"}"""
            } else {
                """{"message":"Unauthorized","detail":"Invalid token"}"""
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseCode, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val secrets = mockContext.secrets
        every { mockContext.settingsStore } returns CoderSettingsStore(
            pluginTestSettingsStore(), Environment(), underlyingLogger,
        )
        every { mockContext.proxySettings.getProxy() } returns Proxy.NO_PROXY
        every { mockContext.proxySettings.getProxyAuth() } returns null
        every { mockClient.url } returns url
        every { secrets.apiTokenFor(url) } returns "saved-token"
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        val handler = mockk<CoderProtocolHandler>(relaxed = true)
        setPrivateField(provider, "client", mockClient)
        setPrivateField(provider, "cli", mockCli)
        setPrivateField(provider, "linkHandler", handler)
        coEvery { provider["refreshSession"](url, "link-token") } returns (mockClient to mockCli)

        try {
            provider.handleUri(workspaceLink(url.toString()))

            assertEquals(listOf("link-token"), requests.toList())
            coVerify(exactly = if (expectsPrompt) 1 else 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
            coVerify(exactly = if (expectsRefresh) 1 else 0) { provider["refreshSession"](url, "link-token") }
            coVerify(exactly = if (expectsRefresh) 1 else 0) { handler.handle(any(), url, mockClient, mockCli) }
            verify(exactly = 0) { secrets.apiTokenFor(any()) }
            verify(exactly = 0) { secrets.storeApiTokenFor(any(), any()) }
            verify(exactly = 0) { secrets.trustDeployment(any()) }
            verify(exactly = 0) { mockClient.close() }
            assertNull(provider.getOverrideUiPage())
        } finally {
            server.stop(0)
        }
    }

    @CsvSource(
        "true,  jetbrains://gateway/com.coder.toolbox?url=https://coder.example.com",
        "false, jetbrains://gateway/com.coder.toolbox?url=https://coder.example.com&token=",
        "false, jetbrains://gateway/com.coder.toolbox?url=https://other.example.com&token=%20",
    )
    @ParameterizedTest(name = "Reject {1} when connected={0}")
    fun `token authentication requires a nonblank token in every workspace link`(
        connected: Boolean,
        link: String,
    ) = runTest {
        val secrets = mockContext.secrets
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        val savedUrl = URI("https://coder.example.com").toURL()
        every { secrets.isUrlTrusted(savedUrl) } returns true
        every { secrets.apiTokenFor(savedUrl) } returns "saved-token"
        if (connected) {
            setPrivateField(remoteProvider, "client", mockClient)
            setPrivateField(remoteProvider, "cli", mockCli)
        }

        remoteProvider.handleUri(URI(link))

        verify {
            underlyingLogger.error(match<String> { it.contains("Query parameter \"token\" is missing from URI") })
        }
        coVerify(exactly = 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        verify(exactly = 0) { secrets.isUrlTrusted(any()) }
        verify(exactly = 0) { secrets.apiTokenFor(any()) }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @Test
    fun `known certificate deployment reopens without a trust prompt`() = runTest {
        every { mockContext.settingsStore.requiresMTlsAuth } returns true
        every { mockContext.settingsStore.requiresTokenAuth } returns false
        every { mockContext.secrets.isUrlTrusted(URI("https://coder.example.com").toURL()) } returns true

        remoteProvider.handleUri(URI("jetbrains://gateway/com.coder.toolbox?url=https://coder.example.com/path"))

        val page = remoteProvider.getOverrideUiPage() as CoderSetupWizardPage
        assertEquals("https://coder.example.com", page.model.url.toString())
        assertEquals(WizardStep.CONNECT, page.model.currentStep())
        assertNull(page.model.token)
        coVerify(exactly = 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
    }

    @Test
    fun `a saved API token cannot identify the account when Toolbox would use OAuth`() = runTest {
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns true
        every { mockContext.secrets.isUrlTrusted(url) } returns true
        every { mockContext.secrets.oauthSessionFor(url.toString()) } returns storedOAuthSession()
        every { mockContext.secrets.apiTokenFor(url) } returns "link-token"
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        coEvery { provider["userForToken"](url, "link-token") } returns DataGen.user()

        provider.handleUri(workspaceLink("https://coder.example.com"))

        coVerify(exactly = 1) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        coVerify(exactly = 1) { provider["userForToken"](url, any<String>()) }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @Test
    fun `dismissing account confirmation preserves the active session without an error popup`() = runTest {
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        val provider = spyk(remoteProvider, recordPrivateCalls = true)
        setPrivateField(provider, "client", mockClient)
        setPrivateField(provider, "cli", mockCli)
        coEvery { provider["userForToken"](url, "link-token") } returns DataGen.user().copy(username = "other-user")
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } throws CancellationException("Dismissed")

        assertFailsWith<CancellationException> {
            provider.handleUri(workspaceLink("https://coder.example.com"))
        }

        coVerify(exactly = 0) { provider["refreshSession"](any<URL>(), any<String>()) }
        verify(exactly = 0) { underlyingLogger.error(any<String>()) }
        verify(exactly = 0) { mockClient.close() }
        assertNull(provider.getOverrideUiPage())
    }

    @Test
    fun `default or last-used URL alone does not establish deployment trust`() = runTest {
        val secrets = mockContext.secrets
        every { mockContext.deploymentUrl } returns URI("https://coder.example.com").toURL()
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false

        remoteProvider.handleUri(workspaceLink("https://coder.example.com"))

        coVerify(exactly = 1) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        verify(exactly = 0) { secrets.trustDeployment(any()) }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @Test
    fun `successful connection remembers the deployment URL and token`() = runTest {
        val url = URI("https://coder.example.com").toURL()
        val secrets = mockContext.secrets
        val settings = CoderSettingsStore(pluginTestSettingsStore(), Environment(), underlyingLogger)
        every { mockContext.settingsStore } returns settings
        every { mockContext.cs } returns backgroundScope
        every { mockClient.token } returns "saved-token"

        completeSignIn()

        verify { secrets.storeApiTokenFor(url, "saved-token") }
        verify { secrets.trustDeployment(url) }
        assertEquals("https://coder.example.com", settings.lastDeploymentURL)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `sign-in behind Settings opens the workspace list when the user returns to setup`() = runTest {
        every { mockContext.cs } returns backgroundScope
        coEvery { mockClient.workspaces(any()) } returns emptyList()
        val wizard = remoteProvider.getOverrideUiPage() as CoderSetupWizardPage
        val pages = mockContext.envPageManager
        wizard.beforeShow()
        runCurrent()
        wizard.afterHide()

        completeSignIn()
        runCurrent()
        verify(exactly = 0) { pages.showPluginEnvironmentsPage(false) }
        assertNull(remoteProvider.getOverrideUiPage())

        wizard.beforeShow()
        wizard.beforeShow()
        runCurrent()
        verify(exactly = 1) { pages.showPluginEnvironmentsPage(false) }
        wizard.afterHide()
        remoteProvider.close()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun completeSignIn() {
        val callback = remoteProvider.javaClass.getDeclaredField("onConnect").apply { isAccessible = true }
            .get(remoteProvider) as SuspendBiConsumer<CoderRestClient, CoderCLIManager>
        callback.accept(mockClient, mockCli)
    }

    @Test
    fun `declining a fresh link preserves the existing setup wizard`() = runTest {
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.secrets.apiTokenFor(any()) } returns null
        val page = remoteProvider.getOverrideUiPage() as CoderSetupWizardPage
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false

        remoteProvider.handleUri(workspaceLink("https://other.example.com"))

        assertSame(page, remoteProvider.getOverrideUiPage())
        assertEquals(WizardStep.URL_REQUEST, page.model.currentStep())
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @Test
    fun `dismissing the trust popup cancels handling without showing an error`() = runTest {
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        setPrivateField(remoteProvider, "client", mockClient)
        setPrivateField(remoteProvider, "cli", mockCli)
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } throws CancellationException("Dismissed")

        assertFailsWith<CancellationException> {
            remoteProvider.handleUri(workspaceLink("https://other.example.com"))
        }

        assertNull(remoteProvider.getOverrideUiPage())
        verify(exactly = 0) { underlyingLogger.error(any<String>()) }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
    }

    @Test
    fun `certificate authentication also requires trust for another deployment`() = runTest {
        every { mockContext.settingsStore.requiresMTlsAuth } returns true
        every { mockContext.settingsStore.requiresTokenAuth } returns false
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns true

        remoteProvider.handleUri(URI("jetbrains://gateway/com.coder.toolbox?url=https://other.example.com"))

        coVerify(exactly = 1) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        val page = remoteProvider.getOverrideUiPage() as CoderSetupWizardPage
        assertEquals(WizardStep.CONNECT, page.model.currentStep())
        assertNull(page.model.token)
    }

    @Test
    fun `trust prompt identifies the destination and token sign-in without exposing credentials`() = runTest {
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        coEvery { mockUi.showYesNoPopup(any(), any(), any(), any()) } returns false
        val i18n = mockContext.i18n

        remoteProvider.handleUri(workspaceLink("https://user:password@OTHER.example.com:8443/path"))

        verify {
            i18n.pnotr(match {
                it.startsWith("https://other.example.com:8443\n\n") &&
                        it.contains("contains a token") && it.contains("SSH configuration") &&
                        !it.contains("password") && !it.contains("link-token")
            })
        }
    }

    @Test
    fun `links without a valid origin cannot start a connection`() = runTest {
        remoteProvider.handleUri(workspaceLink("https://coder.example.com:99999"))

        coVerify(exactly = 0) { mockUi.showYesNoPopup(any(), any(), any(), any()) }
        verify(exactly = 0) { mockContext.popupPluginMainPage() }
        verify { underlyingLogger.error(match<String> { it.contains("valid HTTP(S) host and port") }) }
    }

    private fun workspaceLink(url: String): URI = URI(
        "jetbrains://gateway/com.coder.toolbox?url=${URLEncoder.encode(url, Charsets.UTF_8)}" +
                "&token=link-token&workspace=dev"
    )

    @Test
    fun `given Toolbox 3_5 provider page then header surface is available and account dropdown starts hidden`() {
        // Toolbox 3.5 hides the whole top section when this flag is false.
        // The Coder header page keeps the deployment URL and account dropdown renderable.
        assertTrue(remoteProvider.canCreateNewEnvironments)
        assertNotNull(remoteProvider.getNewEnvironmentUiPage())

        val accountDropdown = assertNotNull(remoteProvider.getAccountDropDown())
        assertFalse(accountDropdown.visibility.value)
    }

    @Test
    fun `given visible account dropdown when provider closes then dropdown is hidden`() {
        val accountDropdown = assertNotNull(remoteProvider.getAccountDropDown())
        accountDropdown.visibility.value = true

        remoteProvider.close()

        assertFalse(accountDropdown.visibility.value)
    }

    @Test
    fun `given mTLS is required when auto setup has stored credentials then mTLS takes precedence`() = runTest {
        every { mockContext.cs } returns backgroundScope
        // given
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns true
        every { mockContext.settingsStore.requiresTokenAuth } returns false
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns true
        every { mockContext.secrets.apiTokenFor(url) } returns "token"
        every { mockContext.secrets.oauthSessionFor(url.toString()) } returns storedOAuthSession()
        val provider = CoderRemoteProvider(mockContext)

        // when
        assertNull(provider.getOverrideUiPage())
        val overridePage = setupWizard(provider)

        // then
        assertEquals(WizardStep.RESTORE_SESSION, overridePage.model.currentStep())
        assertNull(overridePage.model.token)
        assertNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is preferred when auto setup has token and OAuth session then OAuth is used`() = runTest {
        every { mockContext.cs } returns backgroundScope
        // given
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns true
        every { mockContext.secrets.apiTokenFor(url) } returns "token"
        every { mockContext.secrets.oauthSessionFor(url.toString()) } returns storedOAuthSession()
        val provider = CoderRemoteProvider(mockContext)

        // when
        assertNull(provider.getOverrideUiPage())
        val overridePage = setupWizard(provider)

        // then
        assertEquals(WizardStep.RESTORE_SESSION, overridePage.model.currentStep())
        assertNull(overridePage.model.token)
        assertNotNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is not preferred when auto setup has token and OAuth session then token is used`() = runTest {
        every { mockContext.cs } returns backgroundScope
        // given
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns false
        every { mockContext.secrets.apiTokenFor(url) } returns "token"
        every { mockContext.secrets.oauthSessionFor(url.toString()) } returns storedOAuthSession()
        val provider = CoderRemoteProvider(mockContext)

        // when
        assertNull(provider.getOverrideUiPage())
        val overridePage = setupWizard(provider)

        // then
        assertEquals(WizardStep.RESTORE_SESSION, overridePage.model.currentStep())
        assertEquals("token", overridePage.model.token)
        assertNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is not preferred when auto setup has API token then token is used`() = runTest {
        every { mockContext.cs } returns backgroundScope
        // given
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns false
        every { mockContext.secrets.apiTokenFor(url) } returns "api-token"
        val provider = CoderRemoteProvider(mockContext)

        // when
        assertNull(provider.getOverrideUiPage())
        val overridePage = setupWizard(provider)

        // then
        assertEquals(WizardStep.RESTORE_SESSION, overridePage.model.currentStep())
        assertEquals("api-token", overridePage.model.token)
        assertNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is not preferred when auto setup has no API token then wizard starts at URL step`() {
        // given
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns false
        every { mockContext.secrets.apiTokenFor(url) } returns null
        val provider = CoderRemoteProvider(mockContext)

        // when
        val overridePage = provider.getOverrideUiPage() as CoderSetupWizardPage

        // then
        assertEquals(WizardStep.URL_REQUEST, overridePage.model.currentStep())
        assertNull(overridePage.model.token)
        assertNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is not preferred when auto setup only has OAuth session then wizard starts at URL step`() {
        // given
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns false
        every { mockContext.secrets.apiTokenFor(url) } returns null
        every { mockContext.secrets.oauthSessionFor(url.toString()) } returns storedOAuthSession()
        val provider = CoderRemoteProvider(mockContext)

        // when
        val overridePage = provider.getOverrideUiPage() as CoderSetupWizardPage

        // then
        assertEquals(WizardStep.URL_REQUEST, overridePage.model.currentStep())
        assertNull(overridePage.model.token)
        assertNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given no existing environment then one is created`() = runTest {
        // given
        val agent = mockAgent("agent1")
        val resource = mockResource(agents = listOf(agent))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

        coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        assertEquals("ws1.agent1", result[0].id)

    }

    @Test
    fun `given multiple workspaces then environments are sorted by id`() = runTest {
        // given
        val agent1 = mockAgent("agent1")
        val agent2 = mockAgent("agent2")
        val agent3 = mockAgent("agent3")

        val resource1 = mockResource(agents = listOf(agent1))
        val resource2 = mockResource(agents = listOf(agent2))
        val resource3 = mockResource(agents = listOf(agent3))

        val ws1 = mockWorkspace("ws3", WorkspaceStatus.RUNNING, listOf(resource1))
        val ws2 = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource2))
        val ws3 = mockWorkspace("ws2", WorkspaceStatus.RUNNING, listOf(resource3))

        coEvery { mockClient.workspaces(any()) } returns listOf(ws2, ws1, ws3)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        assertEquals(3, result.size)
        assertEquals("ws1.agent2", result[0].id)
        assertEquals("ws2.agent3", result[1].id)
        assertEquals("ws3.agent1", result[2].id)
    }


    @Test
    fun `given workspace with multiple resources and multiple agents when resolving then returns all combinations`() =
        runTest {
            // given
            val agent1 = mockAgent("agent1")
            val agent2 = mockAgent("agent2")
            val agent3 = mockAgent("agent3")
            val agent4 = mockAgent("agent4")

            val resource1 = mockResource(agents = listOf(agent1, agent2))
            val resource2 = mockResource(agents = listOf(agent3, agent4))

            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource1, resource2))
            coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(4, result.size)
            assertEquals(
                setOf("ws1.agent1", "ws1.agent2", "ws1.agent3", "ws1.agent4"),
                result.map { it.id }.toSet()
            )
        }

    @Test
    fun `given three agents with same name in one resource when resolving then returns only one distinct environment`() =
        runTest {
            // given
            val agent1 = mockAgent("duplicate")
            val agent2 = mockAgent("duplicate")
            val agent3 = mockAgent("duplicate")
            val resource = mockResource(agents = listOf(agent1, agent2, agent3))
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))
            coEvery { mockClient.workspaces(any()) } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(1, result.size)
            assertEquals("ws1.duplicate", result[0].id)
        }

    @Test
    fun `given multiple workspaces with agents of same name when resolving then returns separate environments per workspace`() =
        runTest {
            // given
            val agent1 = mockAgent("mockAgent")
            val agent2 = mockAgent("mockAgent")
            val resource1 = mockResource(agents = listOf(agent1))
            val resource2 = mockResource(agents = listOf(agent2))
            val ws1 = mockWorkspace("workspace1", WorkspaceStatus.RUNNING, listOf(resource1))
            val ws2 = mockWorkspace("workspace2", WorkspaceStatus.RUNNING, listOf(resource2))
            coEvery { mockClient.workspaces(any()) } returns listOf(ws1, ws2)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(2, result.size)
            assertEquals("workspace1.mockAgent", result[0].id)
            assertEquals("workspace2.mockAgent", result[1].id)
        }

    // Helper functions
    private fun mockAgent(name: String, status: WorkspaceAgentStatus = WorkspaceAgentStatus.CONNECTED): WorkspaceAgent {
        return mockk {
            every { this@mockk.id } returns UUID.randomUUID()
            every { this@mockk.name } returns name
            every { this@mockk.status } returns status
            every { this@mockk.lifecycleState } returns WorkspaceAgentLifecycleState.READY
        }
    }

    private fun mockResource(agents: List<WorkspaceAgent>?): WorkspaceResource {
        return mockk {
            every { this@mockk.agents } returns agents
        }
    }

    private fun mockWorkspace(
        name: String,
        status: WorkspaceStatus,
        resources: List<WorkspaceResource>
    ): Workspace {
        val latestBuild = mockk<WorkspaceBuild> {
            every { this@mockk.id } returns UUID.randomUUID()
            every { this@mockk.status } returns status
            every { this@mockk.resources } returns resources
        }
        return mockk {
            every { this@mockk.name } returns name
            every { this@mockk.ownerName } returns "owner"
            every { this@mockk.latestBuild } returns latestBuild
            every { this@mockk.templateDisplayName } returns name
            every { this@mockk.outdated } returns false
        }
    }

    private fun setPrivateField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun storedOAuthSession(): StoredOAuthSession = StoredOAuthSession(
        clientId = "client-id",
        clientSecret = "client-secret",
        refreshToken = "refresh-token",
        tokenAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
        tokenEndpoint = "https://coder.example.com/oauth/token"
    )
}
