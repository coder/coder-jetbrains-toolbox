package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceWatchEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceProgressWatcherTest {
    @Test
    fun `workspace snapshots select the build while build logs provide progress output`() = runTest {
        val fixture = Fixture(backgroundScope, initialStatus = WorkspaceStatus.STOPPED)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        stream.onOpen()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertTrue(fixture.watcher.isActive)
        assertEquals(emptyList(), fixture.builds)

        val activeBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID(), status = WorkspaceStatus.STARTING)
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace.copy(latestBuild = activeBuild)))
        runCurrent()
        val logs = fixture.buildStreams.getValue(activeBuild.id).single()
        logs.onOpen()
        logs.onMessage(log(1, "Applying workspace resources"))
        runCurrent()
        assertEquals(listOf(activeBuild), fixture.builds)
        assertEquals(listOf("Applying workspace resources"), fixture.output)
        assertEquals(emptyList(), fixture.failures)

        val completedBuild = activeBuild.copy(status = WorkspaceStatus.RUNNING)
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace.copy(latestBuild = completedBuild)))
        runCurrent()
        assertFalse(fixture.watcher.isActive)
        assertEquals(listOf(activeBuild, completedBuild), fixture.builds)
        verify(exactly = 1) { stream.socket.cancel() }
        verify(exactly = 1) { logs.socket.cancel() }
    }

    @Test
    fun `workspace stream follows an already active initial build`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        stream.onOpen()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        assertEquals(listOf(fixture.workspace.latestBuild), fixture.builds)
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()

        val completedBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.RUNNING)
        fixture.watcher.onWorkspacePolled(fixture.workspace.copy(latestBuild = completedBuild))
        runCurrent()
        assertFalse(fixture.watcher.isActive)
        assertEquals(listOf(fixture.workspace.latestBuild, completedBuild), fixture.builds)
        verify(exactly = 1) { stream.socket.cancel() }
        verify(exactly = 1) { logs.socket.cancel() }
    }

    @Test
    fun `workspace socket failure schedules a reconnect`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        val failure = IOException("WebSockets blocked")
        stream.onFailure(failure)
        runCurrent()
        assertTrue(fixture.watcher.isActive)
        assertEquals(failure.message, fixture.failures.single().message)
        verify(exactly = 1) { stream.socket.cancel() }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, fixture.workspaceStreams.size)
        fixture.watcher.close()
    }

    @Test
    fun `abnormal build log closure retries logs without replacing the workspace stream`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        stream.onOpen()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        logs.onClosed(1011, "Internal error")
        runCurrent()
        assertTrue(fixture.watcher.isActive)
        assertEquals("Workspace build log WebSocket closed: 1011 Internal error", fixture.failures.single().message)
        verify(exactly = 0) { stream.socket.cancel() }
        verify(exactly = 1) { logs.socket.cancel() }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, fixture.workspaceStreams.size)
        assertEquals(2, fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).size)
        fixture.watcher.close()
    }

    @Test
    fun `unexpected socket failures reconnect with backoff and close cancels retries`() = runTest {
        val fixture = Fixture(backgroundScope, initialStatus = WorkspaceStatus.STOPPED)
        runCurrent()
        val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        for ((index, delayMs) in delays.withIndex()) {
            fixture.workspaceStreams.last().onOpen()
            fixture.workspaceStreams.last().onFailure(IOException("connection dropped"))
            runCurrent()
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(index + 1, fixture.workspaceStreams.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(index + 2, fixture.workspaceStreams.size)
        }
        fixture.workspaceStreams.last().onOpen()
        fixture.workspaceStreams.last().onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        fixture.workspaceStreams.last().onClosed(1006, "unexpected closure")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(delays.size + 2, fixture.workspaceStreams.size)

        fixture.workspaceStreams.last().onFailure(IOException("connection dropped"))
        runCurrent()
        fixture.watcher.close()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(delays.size + 2, fixture.workspaceStreams.size)
    }

    @Test
    fun `callbacks from a replaced build cannot affect the current build`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        stream.onOpen()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        val oldLogs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        every { oldLogs.socket.cancel() } answers { oldLogs.onFailure(IOException("cancelled")) }
        val nextWorkspace =
            fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID()))
        stream.onMessage(WorkspaceWatchEvent("data", nextWorkspace))
        runCurrent()
        oldLogs.onOpen()
        oldLogs.onMessage(log(100, "Old build output"))
        oldLogs.onClosed(1011, "Old build closed")
        oldLogs.onFailure(IOException("Old build failed"))
        fixture.buildStreams.getValue(nextWorkspace.latestBuild.id).single().onMessage(log(1, "New build output"))
        runCurrent()
        assertEquals(listOf("New build output"), fixture.output)
        assertEquals(emptyList(), fixture.failures)
        verify(exactly = 1) { oldLogs.socket.cancel() }
        verify(exactly = 0) { stream.socket.cancel() }
        fixture.watcher.close()
    }

    @Test
    fun `replacing a streaming build cancels its entire session and resets progress`() = runTest {
        val fixture = Fixture(backgroundScope)
        val oldResponse = CompletableDeferred<List<ProvisionerJobLog>>()
        val oldRequestFinished = CompletableDeferred<Unit>()
        coEvery { fixture.client.workspaceBuildLogs(fixture.workspace.latestBuild.id) } coAnswers {
            try {
                oldResponse.await()
            } finally {
                oldRequestFinished.complete(Unit)
            }
        }
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        val workspaceStream = fixture.workspaceStreams.single()
        val oldLogs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        oldLogs.onOpen()
        oldLogs.onMessage(log(100, "Old build output"))
        runCurrent()

        // Repeated snapshots retain the same session, including its cursor and in-flight request.
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        oldLogs.onMessage(log(100, "Duplicate output"))
        runCurrent()
        assertFalse(oldRequestFinished.isCompleted)
        assertEquals(1, fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).size)
        assertEquals(listOf("Old build output"), fixture.output)

        val nextWorkspace = fixture.workspace.copy(
            latestBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID()),
        )
        workspaceStream.onMessage(WorkspaceWatchEvent("data", nextWorkspace))
        runCurrent()
        assertTrue(oldRequestFinished.isCompleted)
        verify(exactly = 1) { oldLogs.socket.cancel() }

        // The replacement session starts in fallback with no log cursor, even if the old one streamed.
        coEvery { fixture.client.workspaceBuildLogs(nextWorkspace.latestBuild.id) } returns
                listOf(log(0, "New build output"))
        fixture.watcher.onWorkspacePolled(nextWorkspace)
        runCurrent()
        assertEquals(listOf("Old build output", "New build output"), fixture.output)
        assertEquals(emptyList(), fixture.failures)
        coVerify(exactly = 1) { fixture.client.workspaceBuildLogs(nextWorkspace.latestBuild.id) }
        fixture.watcher.close()
    }

    @Test
    fun `a late poll response cannot replace output from a newer build`() = runTest {
        for (failOldRequest in listOf(false, true)) {
            val fixture = Fixture(backgroundScope, webSocketsEnabled = false)
            val oldResponse = CompletableDeferred<List<ProvisionerJobLog>>()
            coEvery { fixture.client.workspaceBuildLogs(fixture.workspace.latestBuild.id) } coAnswers {
                withContext(NonCancellable) { oldResponse.await() }
            }
            fixture.watcher.onWorkspacePolled(fixture.workspace)
            runCurrent()
            val nextWorkspace =
                fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID()))
            coEvery { fixture.client.workspaceBuildLogs(nextWorkspace.latestBuild.id) } returns listOf(
                log(
                    1,
                    "New build output"
                )
            )
            fixture.watcher.onWorkspacePolled(nextWorkspace)
            runCurrent()
            if (failOldRequest) oldResponse.completeExceptionally(IOException("Old request failed"))
            else oldResponse.complete(listOf(log(100, "Old build output")))
            fixture.watcher.onWorkspacePolled(nextWorkspace)
            runCurrent()
            assertEquals(listOf("New build output", "New build output"), fixture.output)
            assertEquals(emptyList(), fixture.failures)
            assertEquals(emptyList(), fixture.workspaceStreams)
            fixture.watcher.close()
        }
    }

    @Test
    fun `polling bridges log reconnects without replaying streamed logs`() = runTest {
        val fixture = Fixture(backgroundScope)
        val buildID = fixture.workspace.latestBuild.id
        coEvery { fixture.client.workspaceBuildLogs(buildID) } returns listOf(log(1, "Preparing"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        val oldLogs = fixture.buildStreams.getValue(buildID).single()
        oldLogs.onOpen()
        oldLogs.onMessage(log(1, "Preparing"))
        oldLogs.onMessage(log(2, "Building"))
        runCurrent()
        oldLogs.onFailure(IOException("Disconnected"))
        runCurrent()
        coEvery { fixture.client.workspaceBuildLogs(buildID) } returns listOf(log(2, "Building"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val newLogs = fixture.buildStreams.getValue(buildID).last()
        oldLogs.onOpen()
        oldLogs.onMessage(log(100, "Old connection output"))
        oldLogs.onFailure(IOException("Another old error"))
        newLogs.onOpen()
        newLogs.onMessage(log(2, "Building"))
        newLogs.onMessage(log(3, "Finishing"))
        runCurrent()

        // Normal log closure leaves the workspace stream in charge of completion.
        newLogs.onClosed(1000, "Done")
        runCurrent()
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertTrue(fixture.watcher.isActive)
        assertEquals(listOf("Preparing", "Building", "Building", "Finishing"), fixture.output)
        assertEquals(1, fixture.failures.size)
        coVerify(exactly = 3) { fixture.client.workspaceBuildLogs(buildID) }
        fixture.watcher.close()
    }

    @Test
    fun `close ignores late callbacks and pending poll responses`() = runTest {
        val fixture = Fixture(backgroundScope)
        val response = CompletableDeferred<List<ProvisionerJobLog>>()
        coEvery { fixture.client.workspaceBuildLogs(any()) } coAnswers { response.await() }
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        fixture.watcher.close()
        assertFalse(fixture.watcher.isActive)
        stream.onOpen()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        stream.onClosed(1006, "Disconnected")
        stream.onFailure(IOException("Late error"))
        logs.onMessage(log(100, "Late stream output"))
        runCurrent()
        response.complete(listOf(log(1, "Late output")))
        fixture.watcher.close()
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertEquals(emptyList(), fixture.output)
        assertEquals(emptyList(), fixture.failures)
        verify(exactly = 1) { stream.socket.cancel() }
        verify(exactly = 1) { logs.socket.cancel() }
    }

    @Test
    fun `failure before a socket is returned cancels it and schedules only one retry`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val failedSocket = mockk<WebSocket>(relaxed = true)
        every { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) } answers {
            lastArg<(Throwable) -> Unit>()(IOException("Failed before returning the socket"))
            failedSocket
        }
        fixture.workspaceStreams.single().onFailure(IOException("Disconnected"))
        runCurrent()
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

    @Test
    fun `polling continues until the build log socket opens`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        stream.onOpen()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        coEvery { fixture.client.workspaceBuildLogs(any()) } returns listOf(log(1, "Connecting"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertEquals(listOf("Connecting"), fixture.output)
        logs.onOpen()
        runCurrent()
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        coVerify(exactly = 1) { fixture.client.workspaceBuildLogs(any()) }
        logs.onFailure(IOException("Log stream lost"))
        runCurrent()
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        coVerify(exactly = 2) { fixture.client.workspaceBuildLogs(any()) }
        fixture.watcher.close()
    }

    @Test
    fun `replaced workspace streams cannot finish the current watch`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val oldStream = fixture.workspaceStreams.single()
        oldStream.onOpen()
        oldStream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        logs.onOpen()
        oldStream.onFailure(IOException("Disconnected"))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        fixture.workspaceStreams.last().onOpen()
        oldStream.onOpen()
        oldStream.onMessage(
            WorkspaceWatchEvent(
                "data", fixture.workspace.copy(
                    latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.RUNNING),
                )
            )
        )
        oldStream.onClosed(1006, "Old stream closed")
        oldStream.onFailure(IOException("Old stream failed"))
        logs.onMessage(log(1, "Still building"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertTrue(fixture.watcher.isActive)
        assertEquals(listOf("Still building"), fixture.output)
        assertEquals(1, fixture.failures.size)
        coVerify(exactly = 0) { fixture.client.workspaceBuildLogs(any()) }
        verify(exactly = 0) { logs.socket.cancel() }
        fixture.watcher.close()
    }

    @Test
    fun `bursts of socket events are delivered without losing the latest output or completion`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val stream = fixture.workspaceStreams.single()
        stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        repeat(200) { logs.onMessage(log(it + 1L, "Output $it")) }
        // Socket callbacks only enqueue events; caller callbacks belong to the watch coroutine.
        assertEquals(emptyList(), fixture.output)
        runCurrent()
        assertEquals((0 until 200).map { "Output $it" }, fixture.output)
        repeat(200) { stream.onMessage(WorkspaceWatchEvent("data", fixture.workspace)) }
        stream.onMessage(
            WorkspaceWatchEvent(
                "data", fixture.workspace.copy(
                    latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.RUNNING),
                )
            )
        )
        runCurrent()
        assertFalse(fixture.watcher.isActive)
        assertEquals(202, fixture.builds.size)
        assertEquals(WorkspaceStatus.RUNNING, fixture.builds.last().status)
    }

    @Test
    fun `cancelling the owning scope cleans up sockets and pending REST requests`() = runTest {
        val watchScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val fixture = Fixture(watchScope)
        val response = CompletableDeferred<List<ProvisionerJobLog>>()
        val requestFinished = CompletableDeferred<Unit>()
        coEvery { fixture.client.workspaceBuildLogs(any()) } coAnswers {
            try {
                response.await()
            } finally {
                requestFinished.complete(Unit)
            }
        }
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertFalse(requestFinished.isCompleted)
        watchScope.cancel()
        runCurrent()
        assertTrue(requestFinished.isCompleted)
        assertFalse(fixture.watcher.isActive)
        verify(exactly = 1) { fixture.workspaceStreams.single().socket.cancel() }
        verify(exactly = 1) { fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single().socket.cancel() }
    }

    @Test
    fun `snapshot delivery returns immediately and slow REST requests do not accumulate`() = runTest {
        val fixture = Fixture(backgroundScope, webSocketsEnabled = false)
        val response = CompletableDeferred<List<ProvisionerJobLog>>()
        val requestFinished = CompletableDeferred<Unit>()
        coEvery { fixture.client.workspaceBuildLogs(any()) } coAnswers {
            try {
                response.await()
            } finally {
                requestFinished.complete(Unit)
            }
        }
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertFalse(requestFinished.isCompleted)
        repeat(10) {
            fixture.watcher.onWorkspacePolled(fixture.workspace)
            runCurrent()
        }
        coVerify(exactly = 1) { fixture.client.workspaceBuildLogs(any()) }
        assertTrue(fixture.watcher.isActive)
        response.complete(listOf(log(1, "Building")))
        runCurrent()
        assertTrue(requestFinished.isCompleted)
        assertEquals(listOf("Building"), fixture.output)
        assertEquals(emptyList(), fixture.failures)
        fixture.watcher.close()
    }

    @Test
    fun `conflated polls keep only the latest queued build`() = runTest {
        val fixture = Fixture(backgroundScope, webSocketsEnabled = false)
        val nextWorkspace =
            fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID()))
        coEvery { fixture.client.workspaceBuildLogs(nextWorkspace.latestBuild.id) } returns listOf(
            log(
                1,
                "Latest build"
            )
        )
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        fixture.watcher.onWorkspacePolled(nextWorkspace)
        runCurrent()
        assertEquals(listOf(nextWorkspace.latestBuild), fixture.builds)
        assertEquals(listOf("Latest build"), fixture.output)
        coVerify(exactly = 0) { fixture.client.workspaceBuildLogs(fixture.workspace.latestBuild.id) }
        coVerify(exactly = 1) { fixture.client.workspaceBuildLogs(nextWorkspace.latestBuild.id) }
        fixture.watcher.close()
    }

    @Test
    fun `conflated polls finish a build even when its active snapshot was skipped`() = runTest {
        for (initiallyActive in listOf(false, true)) {
            val fixture = Fixture(
                backgroundScope, webSocketsEnabled = false,
                initialStatus = if (initiallyActive) WorkspaceStatus.STARTING else WorkspaceStatus.STOPPED,
            )
            val activeBuild = fixture.workspace.latestBuild.copy(
                id = if (initiallyActive) fixture.workspace.latestBuild.id else UUID.randomUUID(),
                status = WorkspaceStatus.STARTING,
            )
            val completedBuild = activeBuild.copy(status = WorkspaceStatus.RUNNING)
            fixture.watcher.onWorkspacePolled(fixture.workspace.copy(latestBuild = activeBuild))
            fixture.watcher.onWorkspacePolled(fixture.workspace.copy(latestBuild = completedBuild))
            runCurrent()
            assertFalse(fixture.watcher.isActive)
            assertEquals(listOf(completedBuild), fixture.builds)
            coVerify(exactly = 0) { fixture.client.workspaceBuildLogs(any()) }
        }
    }

    @Test
    fun `normal log closure resumes REST polling until the workspace reports completion`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val workspaceStream = fixture.workspaceStreams.single()
        workspaceStream.onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        logs.onOpen()
        logs.onClosed(1000, "Closing connection")
        runCurrent()
        coEvery { fixture.client.workspaceBuildLogs(any()) } returns listOf(log(1, "Still building"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertEquals(listOf("Still building"), fixture.output)
        assertEquals(emptyList(), fixture.failures)
        assertTrue(fixture.watcher.isActive)
        fixture.watcher.onWorkspacePolled(
            fixture.workspace.copy(
                latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.RUNNING),
            )
        )
        runCurrent()
        assertFalse(fixture.watcher.isActive)
    }

    @Test
    fun `a completed workspace stream leaves polling available`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.workspaceStreams.single().onFailure(CancellationException("Stream cancelled"))
        runCurrent()
        coEvery { fixture.client.workspaceBuildLogs(any()) } returns listOf(log(1, "Polling progress"))
        fixture.watcher.onWorkspacePolled(fixture.workspace)
        runCurrent()
        assertTrue(fixture.watcher.isActive)
        assertEquals(listOf("Polling progress"), fixture.output)
        fixture.watcher.close()
    }

    @Test
    fun `the first streamed log can have id zero and is not replayed`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.workspaceStreams.single().onMessage(WorkspaceWatchEvent("data", fixture.workspace))
        runCurrent()
        val logs = fixture.buildStreams.getValue(fixture.workspace.latestBuild.id).single()
        logs.onMessage(log(0, "First output"))
        logs.onMessage(log(0, "First output"))
        runCurrent()
        assertEquals(listOf("First output"), fixture.output)
        fixture.watcher.close()
    }

    @Test
    fun `close waits for an executing callback and prevents subsequent callbacks`() {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val client = mockk<CoderRestClient>()
            val workspaceStreams = LinkedBlockingQueue<Stream<WorkspaceWatchEvent>>()
            val buildStreams = LinkedBlockingQueue<Stream<ProvisionerJobLog>>()
            every { client.streamWorkspace(any(), any(), any(), any(), any()) } answers {
                Stream<WorkspaceWatchEvent>(secondArg(), thirdArg(), arg(3), arg(4))
                    .also(workspaceStreams::add).socket
            }
            every { client.streamWorkspaceBuildLogs(any(), any(), any(), any(), any()) } answers {
                Stream<ProvisionerJobLog>(secondArg(), thirdArg(), arg(3), arg(4))
                    .also(buildStreams::add).socket
            }
            val workspace = DataGen.workspace("closing").let {
                it.copy(latestBuild = it.latestBuild.copy(status = WorkspaceStatus.STARTING))
            }
            val outputStarted = CountDownLatch(1)
            val releaseOutput = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val closeReturned = CountDownLatch(1)
            val outputCount = AtomicInteger()
            val watcher = WorkspaceProgressWatcher(workspace, true, scope, client, {}, {
                outputCount.incrementAndGet()
                outputStarted.countDown()
                check(releaseOutput.await(5, TimeUnit.SECONDS))
            }, { _, _ -> })
            try {
                val stream = assertNotNull(workspaceStreams.poll(5, TimeUnit.SECONDS))
                stream.onMessage(WorkspaceWatchEvent("data", workspace))
                val logs = assertNotNull(buildStreams.poll(5, TimeUnit.SECONDS))
                logs.onMessage(log(1, "Building"))
                assertTrue(outputStarted.await(5, TimeUnit.SECONDS))
                assertTrue(watcher.isActive)
                val closer = thread(isDaemon = true) {
                    closeStarted.countDown()
                    watcher.close()
                    closeReturned.countDown()
                }
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
                // Enqueuing socket events remains nonblocking while the caller's callback is busy.
                stream.onMessage(WorkspaceWatchEvent("data", workspace))
                releaseOutput.countDown()
                assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
                closer.join()
                logs.onMessage(log(2, "Late output"))
                assertFalse(watcher.isActive)
                assertEquals(1, outputCount.get())
            } finally {
                releaseOutput.countDown()
                watcher.close()
                scope.cancel()
            }
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        webSocketsEnabled: Boolean = true,
        initialStatus: WorkspaceStatus = WorkspaceStatus.STARTING,
    ) {
        val client = mockk<CoderRestClient>()
        val workspace = DataGen.workspace("progress").let {
            it.copy(latestBuild = it.latestBuild.copy(status = initialStatus))
        }
        val workspaceStreams = mutableListOf<Stream<WorkspaceWatchEvent>>()
        val buildStreams = mutableMapOf<UUID, MutableList<Stream<ProvisionerJobLog>>>()
        val builds = mutableListOf<WorkspaceBuild>()
        val output = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val watcher: WorkspaceProgressWatcher

        init {
            coEvery { client.workspaceBuildLogs(any()) } returns emptyList()
            every { client.streamWorkspace(any(), any(), any(), any(), any()) } answers {
                Stream<WorkspaceWatchEvent>(secondArg(), thirdArg(), arg(3), arg(4))
                    .also(workspaceStreams::add).socket
            }
            every { client.streamWorkspaceBuildLogs(any(), any(), any(), any(), any()) } answers {
                Stream<ProvisionerJobLog>(secondArg(), thirdArg(), arg(3), arg(4))
                    .also { buildStreams.getOrPut(firstArg()) { mutableListOf() }.add(it) }.socket
            }
            watcher = WorkspaceProgressWatcher(
                workspace, webSocketsEnabled, scope, client,
                onBuild = builds::add,
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
