package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test

class ConnectionMonitoringServiceTest {

    private val context = mockk<CoderToolboxContext>(relaxed = true)

    @Test
    fun `given a running workspace with a timed out agent and a ready lifecycle then expect a connection unstable notification`() {
        val service = ConnectionMonitoringService(context)
        val workspace = createWorkspace(WorkspaceStatus.RUNNING)
        val agent = createAgent(WorkspaceAgentStatus.TIMEOUT, WorkspaceAgentLifecycleState.READY)

        service.checkConnectionStatus(workspace, agent)

        verify(exactly = 1) { context.logAndShowWarning(any(), any()) }
    }

    @Test
    fun `given a running workspace with a disconnected agent and a ready lifecycle then expect a connection unstable notification`() {
        val service = ConnectionMonitoringService(context)
        val workspace = createWorkspace(WorkspaceStatus.RUNNING)
        val agent = createAgent(WorkspaceAgentStatus.DISCONNECTED, WorkspaceAgentLifecycleState.READY)

        service.checkConnectionStatus(workspace, agent)

        verify(exactly = 1) { context.logAndShowWarning(any(), any()) }
    }

    @Test
    fun `given a stopped workspace then expect no notification`() {
        val service = ConnectionMonitoringService(context)
        val workspace = createWorkspace(WorkspaceStatus.STOPPED)
        val agent = createAgent(WorkspaceAgentStatus.DISCONNECTED, WorkspaceAgentLifecycleState.READY)

        service.checkConnectionStatus(workspace, agent)

        verify(exactly = 0) { context.logAndShowWarning(any(), any()) }
    }

    @Test
    fun `given a running workspace with a disconnected agent and a starting lifecycle then expect no notification`() {
        val service = ConnectionMonitoringService(context)
        val workspace = createWorkspace(WorkspaceStatus.RUNNING)
        val agent = createAgent(WorkspaceAgentStatus.DISCONNECTED, WorkspaceAgentLifecycleState.STARTING)

        service.checkConnectionStatus(workspace, agent)

        verify(exactly = 0) { context.logAndShowWarning(any(), any()) }
    }

    @Test
    fun `given a running workspace with a disconnected agent and a ready lifecycle then expect that user is notified only once`() {
        val service = ConnectionMonitoringService(context)
        val workspace = createWorkspace(WorkspaceStatus.RUNNING)
        val agent = createAgent(WorkspaceAgentStatus.DISCONNECTED, WorkspaceAgentLifecycleState.READY)

        // First call triggers notification
        service.checkConnectionStatus(workspace, agent)

        // Reset mocks to verify subsequent calls
        clearMocks(context, answers = false)

        // Second call should not trigger notification
        service.checkConnectionStatus(workspace, agent)

        verify(exactly = 0) { context.logAndShowWarning(any(), any()) }
    }

    @Test
    fun `given a running workspace with a timed out agent and a ready lifecycle then expect that user is notified only once`() {
        val service = ConnectionMonitoringService(context)
        val workspace = createWorkspace(WorkspaceStatus.RUNNING)
        val agent = createAgent(WorkspaceAgentStatus.TIMEOUT, WorkspaceAgentLifecycleState.READY)

        // First call triggers notification
        service.checkConnectionStatus(workspace, agent)

        // Second call should not trigger notification
        service.checkConnectionStatus(workspace, agent)

        verify(exactly = 1) { context.logAndShowWarning(any(), any()) }
    }

    @Test
    fun `given two running workspaces with disconnected agents and ready lifecycles then expect that user is notified only once`() {
        val service = ConnectionMonitoringService(context)
        val ws1 = createWorkspace(WorkspaceStatus.RUNNING)
        val ws2 = createWorkspace(WorkspaceStatus.RUNNING)
        val agent1 = createAgent(WorkspaceAgentStatus.DISCONNECTED, WorkspaceAgentLifecycleState.READY)
        val agent2 = createAgent(WorkspaceAgentStatus.DISCONNECTED, WorkspaceAgentLifecycleState.READY)

        // First call triggers notification
        service.checkConnectionStatus(ws1, agent1)

        // Second call should not trigger notification
        service.checkConnectionStatus(ws2, agent2)

        verify(exactly = 1) { context.logAndShowWarning(any(), any()) }
    }

    @Test
    fun `given two running workspaces with timed out agents and ready lifecycles then expect that user is notified only once`() {
        val service = ConnectionMonitoringService(context)
        val ws1 = createWorkspace(WorkspaceStatus.RUNNING)
        val ws2 = createWorkspace(WorkspaceStatus.RUNNING)
        val agent1 = createAgent(WorkspaceAgentStatus.TIMEOUT, WorkspaceAgentLifecycleState.READY)
        val agent2 = createAgent(WorkspaceAgentStatus.TIMEOUT, WorkspaceAgentLifecycleState.READY)

        // First call triggers notification
        service.checkConnectionStatus(ws1, agent1)

        // Second call should not trigger notification
        service.checkConnectionStatus(ws2, agent2)

        verify(exactly = 1) { context.logAndShowWarning(any(), any()) }
    }


    private fun createWorkspace(status: WorkspaceStatus): Workspace {
        return Workspace(
            id = UUID.randomUUID(),
            templateID = UUID.randomUUID(),
            templateName = "template",
            templateDisplayName = "Template",
            templateIcon = "icon",
            latestBuild = WorkspaceBuild(
                id = UUID.randomUUID(),
                buildNumber = 1,
                templateVersionID = UUID.randomUUID(),
                resources = emptyList(),
                status = status
            ),
            outdated = false,
            name = "workspace-${UUID.randomUUID()}",
            ownerName = "owner"
        )
    }

    private fun createAgent(
        status: WorkspaceAgentStatus,
        lifecycleState: WorkspaceAgentLifecycleState
    ): WorkspaceAgent {
        return WorkspaceAgent(
            id = UUID.randomUUID(),
            status = status,
            name = "agent-${UUID.randomUUID()}",
            architecture = null,
            operatingSystem = null,
            directory = null,
            expandedDirectory = null,
            lifecycleState = lifecycleState,
            loginBeforeReady = false
        )
    }
}
