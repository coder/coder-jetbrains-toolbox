package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceWatchEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.WebSocket
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkspaceProgressWatcherTest {
    @Test
    fun `workspace snapshots select the build while build logs provide progress output`() {
        val client = mockk<CoderRestClient>()
        val workspaceSocket = mockk<WebSocket>(relaxed = true)
        val buildLogsSocket = mockk<WebSocket>(relaxed = true)
        val workspace = DataGen.workspace("live-progress")
        val workspaceMessage = slot<(WorkspaceWatchEvent) -> Unit>()
        every {
            client.streamWorkspace(
                workspace.id,
                capture(workspaceMessage),
                any(),
                any(),
            )
        } returns workspaceSocket
        val builds = mutableListOf<WorkspaceBuild>()
        val output = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val watcher = WorkspaceProgressWatcher(
            workspace,
            client,
            onBuild = builds::add,
            onOutput = output::add,
            onFailure = failures::add,
        )

        workspaceMessage.captured(WorkspaceWatchEvent("data", workspace))
        assertEquals(emptyList(), builds)

        val activeBuild = workspace.latestBuild.copy(
            id = UUID.randomUUID(),
            status = WorkspaceStatus.STARTING,
        )
        val buildLogMessage = slot<(ProvisionerJobLog) -> Unit>()
        every {
            client.streamWorkspaceBuildLogs(
                activeBuild.id,
                capture(buildLogMessage),
                any(),
                any(),
            )
        } returns buildLogsSocket
        workspaceMessage.captured(WorkspaceWatchEvent("data", workspace.copy(latestBuild = activeBuild)))
        val log = ProvisionerJobLog(
            id = 1,
            createdAt = Instant.EPOCH,
            source = "provisioner",
            level = "info",
            stage = "Building",
            output = "Applying workspace resources",
        )
        buildLogMessage.captured(log)

        assertEquals(listOf(activeBuild), builds)
        assertEquals(listOf("Applying workspace resources"), output)
        assertEquals(emptyList(), failures)

        val completedBuild = activeBuild.copy(status = WorkspaceStatus.RUNNING)
        workspaceMessage.captured(WorkspaceWatchEvent("data", workspace.copy(latestBuild = completedBuild)))

        assertFalse(watcher.isActive)
        assertEquals(listOf(activeBuild, completedBuild), builds)
        verify(exactly = 1) { workspaceSocket.close(1000, any()) }
        verify(exactly = 1) { buildLogsSocket.close(1000, any()) }
    }

    @Test
    fun `workspace socket failure makes the watcher unavailable`() {
        val client = mockk<CoderRestClient>()
        val socket = mockk<WebSocket>(relaxed = true)
        val onFailure = slot<(Throwable) -> Unit>()
        every {
            client.streamWorkspace(
                any(),
                any(),
                capture(onFailure),
                any(),
            )
        } returns socket
        val failure = IllegalStateException("WebSockets blocked")
        val failures = mutableListOf<Throwable>()
        val watcher = WorkspaceProgressWatcher(
            DataGen.workspace("failed-progress"),
            client,
            onBuild = {},
            onOutput = {},
            onFailure = failures::add,
        )

        onFailure.captured(failure)

        assertFalse(watcher.isActive)
        assertEquals(listOf<Throwable>(failure), failures)
        verify(exactly = 1) { socket.cancel() }
    }

    @Test
    fun `abnormal build log closure makes the watcher unavailable`() {
        val client = mockk<CoderRestClient>()
        val workspaceSocket = mockk<WebSocket>(relaxed = true)
        val buildLogsSocket = mockk<WebSocket>(relaxed = true)
        every { client.streamWorkspace(any(), any(), any(), any()) } returns workspaceSocket
        val onBuildLogsClosed = slot<(Int, String) -> Unit>()
        every {
            client.streamWorkspaceBuildLogs(
                any(),
                any(),
                any(),
                capture(onBuildLogsClosed),
            )
        } returns buildLogsSocket
        val failures = mutableListOf<Throwable>()
        val watcher = WorkspaceProgressWatcher(
            DataGen.workspace("closed-build-logs"),
            client,
            onBuild = {},
            onOutput = {},
            onFailure = failures::add,
        )

        watcher.watchBuild(DataGen.workspace("active-build").latestBuild.copy(status = WorkspaceStatus.STARTING))
        onBuildLogsClosed.captured(1011, "Internal error")

        assertFalse(watcher.isActive)
        assertEquals(1, failures.size)
        assertEquals(
            "Workspace build log WebSocket closed: 1011 Internal error",
            failures.single().message,
        )
        verify(exactly = 1) { workspaceSocket.cancel() }
        verify(exactly = 1) { buildLogsSocket.cancel() }
    }
}
