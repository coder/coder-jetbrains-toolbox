package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        remoteProvider = CoderRemoteProvider(mockContext)
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `given an empty workspace list expect an empty list of environments`() = runTest {
        // given
        coEvery { mockClient.workspaces() } returns emptyList()
        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)
        // then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `given a running workspace with two agents then two environments are returned`() = runTest {
        // given
        val agent1 = mockAgent("agent1")
        val agent2 = mockAgent("agent2")
        val resource = mockResource(agents = listOf(agent1, agent2))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

        coEvery { mockClient.workspaces() } returns listOf(workspace)
        coEvery { mockClient.resources(any()) } returns emptyList()

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(2, result.size)
        assertEquals("ws1.agent1", result[0].id)
        assertEquals("ws1.agent2", result[1].id)
        coVerify(exactly = 0) { mockClient.resources(workspace) }
    }

    @Test
    fun `given a stopped workspace then resources are fetched separately`() = runTest {
        // given
        val agent = mockAgent("agent1")
        val resource = mockResource(agents = listOf(agent))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.STOPPED, emptyList())

        coEvery { mockClient.workspaces() } returns listOf(workspace)
        coEvery { mockClient.resources(any()) } returns listOf(resource)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        assertEquals("ws1.agent1", result[0].id)
        coVerify(exactly = 1) { mockClient.resources(workspace) }
    }

    @Test
    fun `given a pending workspace then resources are fetched separately`() = runTest {
        // given
        val agent = mockAgent("agent1")
        val resource = mockResource(agents = listOf(agent))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.PENDING, emptyList())

        coEvery { mockClient.workspaces() } returns listOf(workspace)
        coEvery { mockClient.resources(workspace) } returns listOf(resource)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        coVerify(exactly = 1) { mockClient.resources(workspace) }
    }

    @Test
    fun `given a running workspace with empty resources then resources are fetched separately`() = runTest {
        // given
        val agent = mockAgent("agent1")
        val resource = mockResource(agents = listOf(agent))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, emptyList())

        coEvery { mockClient.workspaces() } returns listOf(workspace)
        coEvery { mockClient.resources(workspace) } returns listOf(resource)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        coVerify(exactly = 1) { mockClient.resources(workspace) }
    }


    @Test
    fun `given a running workspace with a resource that has no agents (ie null) then no environment is returned`() =
        runTest {
            // given
            val resource = mockResource(agents = null)
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

            coEvery { mockClient.workspaces() } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertTrue(result.isEmpty())
            coVerify(exactly = 0) { mockClient.resources(workspace) }
        }

    @Test
    fun `given a running workspace with a resource that has an empty list of agents then no environment is returned`() =
        runTest {
            // given
            val resource = mockResource(agents = emptyList())
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

            coEvery { mockClient.workspaces() } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertTrue(result.isEmpty())
            coVerify(exactly = 0) { mockClient.resources(workspace) }
        }

    @Test
    fun `given a running workspace with a resource that has two two agents with same name but different ids then returns only one environment is resolved`() =
        runTest {
            // given
            val agent1 = mockAgent("agent1")
            val agent2 = mockAgent("agent1") // Same name, different ID
            val resource = mockResource(agents = listOf(agent1, agent2))
            val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

            coEvery { mockClient.workspaces() } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(1, result.size)
            assertEquals("ws1.agent1", result[0].id)
            coVerify(exactly = 0) { mockClient.resources(workspace) }
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

            coEvery { mockClient.workspaces() } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(1, result.size)
            assertEquals("ws1.agent1", result[0].id)
            coVerify(exactly = 0) { mockClient.resources(workspace) }
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
        coEvery { mockClient.workspaces() } returns listOf(workspace)

        // when
        val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

        // then
        assertEquals(1, result.size)
        assertSame(existingEnv, result[0])
        coVerify(exactly = 1) { existingEnv.update(workspace, agent) }
    }

    @Test
    fun `given no existing environment then one is created`() = runTest {
        // given
        val agent = mockAgent("agent1")
        val resource = mockResource(agents = listOf(agent))
        val workspace = mockWorkspace("ws1", WorkspaceStatus.RUNNING, listOf(resource))

        coEvery { mockClient.workspaces() } returns listOf(workspace)

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

        coEvery { mockClient.workspaces() } returns listOf(ws2, ws1, ws3)

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
            coEvery { mockClient.workspaces() } returns listOf(workspace)

            // when
            val result = remoteProvider.resolveWorkspaceEnvironments(mockClient, mockCli)

            // then
            assertEquals(4, result.size)
            assertEquals(
                setOf("ws1.agent1", "ws1.agent2", "ws1.agent3", "ws1.agent4"),
                result.map { it.id }.toSet()
            )
            coVerify(exactly = 0) { mockClient.resources(workspace) }
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
            coEvery { mockClient.workspaces() } returns listOf(workspace)

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
            coEvery { mockClient.workspaces() } returns listOf(ws1, ws2)

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
}