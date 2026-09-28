package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceWatchEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 4) { client.streamWorkspace(any(), any(), any(), any(), any()) }

        var attempts = 4
        for (delayMs in listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            failures.last()(IOException("reconnect failed"))
            advanceTimeBy(delayMs - 1)
            runCurrent()
            verify(exactly = attempts) { client.streamWorkspace(any(), any(), any(), any(), any()) }
            advanceTimeBy(1)
            runCurrent()
            attempts++
            verify(exactly = attempts) { client.streamWorkspace(any(), any(), any(), any(), any()) }
        }

        failures.last()(IOException("connection dropped"))
        watcher.close()
        advanceTimeBy(30_000)
        runCurrent()
        verify(exactly = attempts) { client.streamWorkspace(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `callbacks from a replaced build cannot affect the current build`() = runTest {
        val fixture = Fixture(backgroundScope)
        val workspaceStream = fixture.workspaceStreams.single()
        workspaceStream.onOpen()
        workspaceStream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        val oldStream = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        // Cancellation can itself trigger a failure callback from the old stream.
        every { oldStream.socket.cancel() } answers { oldStream.onFailure(IOException("cancelled")) }
        val nextWorkspace = fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID()))

        workspaceStream.onMessage(WorkspaceWatchEvent("data", nextWorkspace))
        assertEquals(emptyList(), fixture.failures)
        oldStream.onOpen()
        oldStream.onMessage(log(100, "Old build output"))
        oldStream.onClosed(1011, "Old build closed")
        oldStream.onFailure(IOException("Old build failed"))
        fixture.buildStreams.getValue(nextWorkspace.latestBuild.id).single().onMessage(log(1, "New build output"))

        assertEquals(listOf("New build output"), fixture.output)
        assertEquals(emptyList(), fixture.failures)
        verify(exactly = 1) { oldStream.socket.cancel() }
        verify(exactly = 0) { workspaceStream.socket.cancel() }
        fixture.watcher.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a late poll response cannot replace output from a newer build`() = runTest {
        for (failOldRequest in listOf(false, true)) {
            val fixture = Fixture(backgroundScope, webSocketsEnabled = false)
            val oldResponse = CompletableDeferred<List<ProvisionerJobLog>>()
            coEvery { fixture.client.workspaceBuildLogs(fixture.workspace.latestBuild.id) } coAnswers { oldResponse.await() }
            val oldPoll = launch { fixture.watcher.onWorkspacePolled(fixture.workspace) }
            runCurrent()
            val nextWorkspace = fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID()))
            coEvery { fixture.client.workspaceBuildLogs(nextWorkspace.latestBuild.id) } returns listOf(log(1, "New build output"))

            fixture.watcher.onWorkspacePolled(nextWorkspace)
            if (failOldRequest) oldResponse.completeExceptionally(IOException("Old request failed"))
            else oldResponse.complete(listOf(log(100, "Old build output")))
            oldPoll.join()
            fixture.watcher.onWorkspacePolled(nextWorkspace)

            assertEquals(listOf("New build output", "New build output"), fixture.output)
            assertEquals(emptyList(), fixture.failures)
            assertEquals(emptyList(), fixture.workspaceStreams)
            fixture.watcher.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `polling bridges reconnects without replaying streamed logs`() = runTest {
        val fixture = Fixture(backgroundScope)
        val buildID = fixture.workspace.latestBuild.id
        coEvery { fixture.client.workspaceBuildLogs(buildID) } returns listOf(log(1, "Preparing"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        val oldConnection = fixture.workspaceStreams.single()
        oldConnection.onOpen()
        val oldLogs = fixture.buildStreams.getValue(buildID).single()
        oldLogs.onMessage(log(1, "Preparing"))
        oldLogs.onMessage(log(2, "Building"))
        oldConnection.onFailure(IOException("Disconnected"))
        coEvery { fixture.client.workspaceBuildLogs(buildID) } returns listOf(log(2, "Building"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)

        advanceTimeBy(1_000)
        runCurrent()
        val newConnection = fixture.workspaceStreams.last()
        oldConnection.onOpen()
        val completedWorkspace = fixture.workspace.copy(
            latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.RUNNING),
        )
        oldConnection.onMessage(WorkspaceWatchEvent("data", completedWorkspace))
        oldConnection.onClosed(1006, "Disconnected")
        oldConnection.onFailure(IOException("Another old error"))
        oldLogs.onMessage(log(100, "Old connection output"))
        newConnection.onOpen()
        val newLogs = fixture.buildStreams.getValue(buildID).last()
        newLogs.onMessage(log(2, "Building"))
        newLogs.onMessage(log(3, "Finishing"))

        // Normal log closure must leave the workspace stream in charge of completion.
        newLogs.onClosed(1000, "Done")
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        assertTrue(fixture.watcher.isActive)
        assertEquals(listOf("Preparing", "Building", "Building", "Finishing"), fixture.output)
        assertEquals(1, fixture.failures.size)
        assertEquals(2, fixture.buildStreams.getValue(buildID).size)
        coVerify(exactly = 2) { fixture.client.workspaceBuildLogs(buildID) }
        fixture.watcher.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `close ignores late callbacks and pending poll responses`() = runTest {
        val fixture = Fixture(backgroundScope)
        val response = CompletableDeferred<List<ProvisionerJobLog>>()
        coEvery { fixture.client.workspaceBuildLogs(any()) } coAnswers { response.await() }
        val poll = launch { fixture.watcher.onWorkspacePolled(fixture.workspace) }
        runCurrent()
        val stream = fixture.workspaceStreams.single()

        fixture.watcher.close()
        stream.onOpen()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        stream.onClosed(1006, "Disconnected")
        stream.onFailure(IOException("Late error"))
        response.complete(listOf(log(1, "Late output")))
        poll.join()
        fixture.watcher.close()

        assertFalse(fixture.watcher.isActive)
        assertEquals(emptyList(), fixture.output)
        assertEquals(emptyList(), fixture.failures)
        assertTrue(fixture.buildStreams.isEmpty())
        verify(exactly = 1) { stream.socket.close(1000, any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `failure before a socket is returned cancels it and schedules only one retry`() = runTest {
        val fixture = Fixture(backgroundScope)
        val failedSocket = mockk<WebSocket>(relaxed = true)
        every { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) } answers {
            lastArg<(Throwable) -> Unit>()(IOException("Failed before returning the socket"))
            failedSocket
        }
        fixture.workspaceStreams.single().onFailure(IOException("Disconnected"))

        advanceTimeBy(1_000)
        runCurrent()

        verify(exactly = 1) { failedSocket.cancel() }
        assertEquals(2, fixture.failures.size)
        advanceTimeBy(1_999)
        runCurrent()
        verify(exactly = 2) { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) }
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 3) { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) }
        fixture.watcher.close()
        advanceTimeBy(30_000)
        runCurrent()
        verify(exactly = 3) { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) }
    }

    private class Fixture(scope: CoroutineScope, webSocketsEnabled: Boolean = true) {
        val client = mockk<CoderRestClient>()
        val workspace = DataGen.workspace("progress").let {
            it.copy(latestBuild = it.latestBuild.copy(status = WorkspaceStatus.STARTING))
        }
        val workspaceStreams = mutableListOf<Stream<WorkspaceWatchEvent>>()
        val buildStreams = mutableMapOf<UUID, MutableList<Stream<ProvisionerJobLog>>>()
        val output = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val watcher: WorkspaceProgressWatcher

        init {
            every { client.streamWorkspace(any(), any(), any(), any(), any()) } answers {
                Stream<WorkspaceWatchEvent>(secondArg(), thirdArg(), arg(3), arg(4))
                    .also(workspaceStreams::add).socket
            }
            every { client.streamWorkspaceBuildLogs(any(), any(), any(), any(), any()) } answers {
                Stream<ProvisionerJobLog>(secondArg(), thirdArg(), arg(3), arg(4))
                    .also { buildStreams.getOrPut(firstArg()) { mutableListOf() }.add(it) }.socket
            }
            watcher = WorkspaceProgressWatcher(
                workspace,
                client,
                workspaceProgressSupportedViaWebSockets = webSocketsEnabled,
                scope = scope,
                onBuild = {},
                onOutput = output::add,
                onFailure = { error, _ -> failures.add(error) },
            )
        }
    }

    private class Stream<T>(
        val onOpen: () -> Unit,
        val onMessage: (T) -> Unit,
        val onClosed: (Int, String) -> Unit,
        val onFailure: (Throwable) -> Unit,
    ) {
        val socket = mockk<WebSocket>(relaxed = true)
    }

    private fun log(id: Long, output: String) = ProvisionerJobLog(
        id = id,
        createdAt = Instant.EPOCH,
        source = "provisioner",
        level = "info",
        stage = "Building",
        output = output,
    )
}
