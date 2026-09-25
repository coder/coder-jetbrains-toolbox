package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceWatchEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceProgressWatcherTest {
    @Test
    fun `workspace snapshots select the build while build logs provide progress output`() = runTest {
        val client = mockk<CoderRestClient>()
        val workspaceSocket = mockk<WebSocket>(relaxed = true)
        val buildLogsSocket = mockk<WebSocket>(relaxed = true)
        val workspace = DataGen.workspace("live-progress")
        val workspaceMessage = slot<(WorkspaceWatchEvent) -> Unit>()
        val workspaceOpen = slot<() -> Unit>()
        every {
            client.streamWorkspace(
                workspace.id,
                capture(workspaceOpen),
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
            workspaceProgressSupportedViaWebSockets = true,
            scope = backgroundScope,
            onBuild = builds::add,
            onOutput = output::add,
            onFailure = { error, _ -> failures.add(error) },
        )
        workspaceOpen.captured()

        workspaceMessage.captured(WorkspaceWatchEvent("data", workspace))
        watcher.onWorkspacePolled(workspace)
        assertTrue(watcher.isActive)
        assertEquals(emptyList(), builds)

        val activeBuild = workspace.latestBuild.copy(
            id = UUID.randomUUID(),
            status = WorkspaceStatus.STARTING,
        )
        val buildLogMessage = slot<(ProvisionerJobLog) -> Unit>()
        every {
            client.streamWorkspaceBuildLogs(
                activeBuild.id,
                any(),
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
    fun `workspace stream follows an already active initial build`() = runTest {
        val client = mockk<CoderRestClient>()
        val workspaceSocket = mockk<WebSocket>(relaxed = true)
        val buildLogsSocket = mockk<WebSocket>(relaxed = true)
        val initialWorkspace = DataGen.workspace("existing-build")
        val workspace = initialWorkspace.copy(
            latestBuild = initialWorkspace.latestBuild.copy(status = WorkspaceStatus.STARTING),
        )
        val workspaceMessage = slot<(WorkspaceWatchEvent) -> Unit>()
        val workspaceOpen = slot<() -> Unit>()
        every {
            client.streamWorkspace(workspace.id, capture(workspaceOpen), capture(workspaceMessage), any(), any())
        } returns workspaceSocket
        every {
            client.streamWorkspaceBuildLogs(workspace.latestBuild.id, any(), any(), any(), any())
        } returns buildLogsSocket
        val builds = mutableListOf<WorkspaceBuild>()
        val watcher = WorkspaceProgressWatcher(
            workspace,
            client,
            workspaceProgressSupportedViaWebSockets = true,
            scope = backgroundScope,
            onBuild = builds::add,
            onOutput = {},
            onFailure = { error, _ -> throw error },
        )
        workspaceOpen.captured()

        workspaceMessage.captured(WorkspaceWatchEvent("data", workspace))

        assertEquals(listOf(workspace.latestBuild), builds)
        verify(exactly = 1) {
            client.streamWorkspaceBuildLogs(workspace.latestBuild.id, any(), any(), any(), any())
        }
        val completedBuild = workspace.latestBuild.copy(status = WorkspaceStatus.RUNNING)
        watcher.onWorkspacePolled(workspace.copy(latestBuild = completedBuild))

        assertFalse(watcher.isActive)
        assertEquals(listOf(workspace.latestBuild, completedBuild), builds)
        verify(exactly = 1) { workspaceSocket.close(1000, any()) }
        verify(exactly = 1) { buildLogsSocket.close(1000, any()) }
    }

    @Test
    fun `workspace socket failure schedules a reconnect`() = runTest {
        val client = mockk<CoderRestClient>()
        val socket = mockk<WebSocket>(relaxed = true)
        val onFailure = slot<(Throwable) -> Unit>()
        every {
            client.streamWorkspace(
                any(),
                any(),
                any(),
                any(),
                capture(onFailure),
            )
        } returns socket
        val failure = IllegalStateException("WebSockets blocked")
        val failures = mutableListOf<Throwable>()
        val watcher = WorkspaceProgressWatcher(
            DataGen.workspace("failed-progress"),
            client,
            workspaceProgressSupportedViaWebSockets = true,
            scope = backgroundScope,
            onBuild = {},
            onOutput = {},
            onFailure = { error, _ -> failures.add(error) },
        )

        onFailure.captured(failure)

        assertTrue(watcher.isActive)
        assertEquals(listOf<Throwable>(failure), failures)
        verify(exactly = 1) { socket.cancel() }
        watcher.close()
    }

    @Test
    fun `abnormal build log closure schedules a reconnect`() = runTest {
        val client = mockk<CoderRestClient>()
        val workspaceSocket = mockk<WebSocket>(relaxed = true)
        val buildLogsSocket = mockk<WebSocket>(relaxed = true)
        val workspaceOpen = slot<() -> Unit>()
        every { client.streamWorkspace(any(), capture(workspaceOpen), any(), any(), any()) } returns workspaceSocket
        val onBuildLogsClosed = slot<(Int, String) -> Unit>()
        every {
            client.streamWorkspaceBuildLogs(
                any(),
                any(),
                any(),
                capture(onBuildLogsClosed),
                any(),
            )
        } returns buildLogsSocket
        val failures = mutableListOf<Throwable>()
        val watcher = WorkspaceProgressWatcher(
            DataGen.workspace("closed-build-logs"),
            client,
            workspaceProgressSupportedViaWebSockets = true,
            scope = backgroundScope,
            onBuild = {},
            onOutput = {},
            onFailure = { error, _ -> failures.add(error) },
        )
        workspaceOpen.captured()

        val activeWorkspace = DataGen.workspace("active-build")
        watcher.onWorkspacePolled(
            activeWorkspace.copy(latestBuild = activeWorkspace.latestBuild.copy(status = WorkspaceStatus.STARTING)),
        )
        onBuildLogsClosed.captured(1011, "Internal error")

        assertTrue(watcher.isActive)
        assertEquals(1, failures.size)
        assertEquals(
            "Workspace build log WebSocket closed: 1011 Internal error",
            failures.single().message,
        )
        verify(exactly = 1) { workspaceSocket.cancel() }
        verify(exactly = 1) { buildLogsSocket.cancel() }
        watcher.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `unexpected socket failures reconnect with backoff and close cancels retries`() = runTest {
        val client = mockk<CoderRestClient>()
        val socket = mockk<WebSocket>(relaxed = true)
        val failures = mutableListOf<(Throwable) -> Unit>()
        val closes = mutableListOf<(Int, String) -> Unit>()
        val opens = mutableListOf<() -> Unit>()
        every {
            client.streamWorkspace(any(), capture(opens), any(), capture(closes), capture(failures))
        } returns socket
        val watcher = WorkspaceProgressWatcher(
            DataGen.workspace("reconnecting-progress"),
            client,
            workspaceProgressSupportedViaWebSockets = true,
            scope = backgroundScope,
            onBuild = {},
            onOutput = {},
            onFailure = { _, _ -> },
        )

        failures[0](IOException("connection dropped"))
        advanceTimeBy(999)
        runCurrent()
        verify(exactly = 1) { client.streamWorkspace(any(), any(), any(), any(), any()) }
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 2) { client.streamWorkspace(any(), any(), any(), any(), any()) }

        failures[1](IOException("reconnect failed"))
        advanceTimeBy(1_999)
        runCurrent()
        verify(exactly = 2) { client.streamWorkspace(any(), any(), any(), any(), any()) }
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 3) { client.streamWorkspace(any(), any(), any(), any(), any()) }

        opens[2]()
        closes[2](1006, "unexpected closure")
        watcher.close()
        advanceTimeBy(30_000)
        runCurrent()
        verify(exactly = 3) { client.streamWorkspace(any(), any(), any(), any(), any()) }
    }
}
