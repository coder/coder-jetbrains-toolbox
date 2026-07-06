package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.views.CoderSetupWizardPage
import com.coder.toolbox.views.state.StoredOAuthSession
import com.coder.toolbox.views.state.WizardStep
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URI
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CoderRemoteProviderTest {

    private lateinit var mockClient: CoderRestClient
    private lateinit var mockCli: CoderCLIManager
    private lateinit var mockContext: CoderToolboxContext
    private lateinit var remoteProvider: CoderRemoteProvider

    @BeforeTest
    fun setup() {
        mockClient = mockk(relaxed = true)
        mockCli = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)
        val settingsStore = mockk<CoderSettingsStore>(relaxed = true)
        every { mockContext.settingsStore } returns settingsStore
        every { mockClient.url } returns URI("https://coder.example.com").toURL()
        remoteProvider = CoderRemoteProvider(mockContext)
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
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
        assertNull(result[0].toWorkspaceAgentPairOrNull())
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
        coVerify(exactly = 1) { existingEnv.update(workspace, agent) }
    }

    @Test
    fun `given connected client when URI targets different deployment then scheduled wizard overrides client`() =
        runTest {
            // given
            every { mockClient.url } returns URI("https://old.example.com").toURL()
            every { mockContext.settingsStore.requiresMTlsAuth } returns false
            every { mockContext.settingsStore.requiresTokenAuth } returns true
            setPrivateField(remoteProvider, "client", mockClient)
            setPrivateField(remoteProvider, "cli", mockCli)

            assertNull(remoteProvider.getOverrideUiPage())

            // when
            remoteProvider.handleUri(
                URI("jetbrains://gateway/com.coder.toolbox?url=https%3A%2F%2Fnew.example.com&token=new-token")
            )

            // then
            val overridePage = remoteProvider.getOverrideUiPage()
            assertNotNull(overridePage)
            assertTrue(overridePage is CoderSetupWizardPage)
            verify { mockContext.popupPluginMainPage() }
        }

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
    fun `given mTLS is required when auto setup has stored credentials then mTLS takes precedence`() {
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
        val overridePage = provider.getOverrideUiPage() as CoderSetupWizardPage

        // then
        assertEquals(WizardStep.CONNECT, overridePage.model.currentStep())
        assertNull(overridePage.model.token)
        assertNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is preferred when auto setup has token and OAuth session then OAuth is used`() {
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
        val overridePage = provider.getOverrideUiPage() as CoderSetupWizardPage

        // then
        assertEquals(WizardStep.CONNECT, overridePage.model.currentStep())
        assertNull(overridePage.model.token)
        assertNotNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is not preferred when auto setup has token and OAuth session then token is used`() {
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
        val overridePage = provider.getOverrideUiPage() as CoderSetupWizardPage

        // then
        assertEquals(WizardStep.CONNECT, overridePage.model.currentStep())
        assertEquals("token", overridePage.model.token)
        assertNull(overridePage.model.oauthSession)
    }

    @Test
    fun `given OAuth is not preferred when auto setup has API token then token is used`() {
        // given
        val url = URI("https://coder.example.com").toURL()
        every { mockContext.deploymentUrl } returns url
        every { mockContext.settingsStore.requiresMTlsAuth } returns false
        every { mockContext.settingsStore.requiresTokenAuth } returns true
        every { mockContext.settingsStore.preferOAuth2IfAvailable } returns false
        every { mockContext.secrets.apiTokenFor(url) } returns "api-token"
        val provider = CoderRemoteProvider(mockContext)

        // when
        val overridePage = provider.getOverrideUiPage() as CoderSetupWizardPage

        // then
        assertEquals(WizardStep.CONNECT, overridePage.model.currentStep())
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
            every { this@mockk.status } returns status
            every { this@mockk.resources } returns resources
        }
        return mockk {
            every { this@mockk.name } returns name
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
