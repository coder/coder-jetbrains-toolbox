package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.convertors.InstantConverter
import com.coder.toolbox.sdk.convertors.UUIDConverter
import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkspaceProgressWatcherTest {
    private val moshi = Moshi.Builder()
        .add(InstantConverter())
        .add(UUIDConverter())
        .build()

    @Test
    fun `workspace snapshots select the build while build logs provide progress output`() {
        val httpClient = mockk<OkHttpClient>()
        val workspaceSocket = mockk<WebSocket>(relaxed = true)
        val buildLogsSocket = mockk<WebSocket>(relaxed = true)
        val requests = mutableListOf<Request>()
        val listeners = mutableListOf<WebSocketListener>()
        every { httpClient.newWebSocket(capture(requests), capture(listeners)) } returnsMany
                listOf(workspaceSocket, buildLogsSocket)
        val workspace = DataGen.workspace("live-progress")
        val builds = mutableListOf<WorkspaceBuild>()
        val output = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val watcher = WebSocketWorkspaceProgressWatcher(
            httpClient,
            moshi,
            URI.create("https://coder.example.com").toURL(),
            workspace,
            WorkspaceProgressCallbacks(builds::add, output::add, failures::add),
        )

        listeners.single().onMessage(workspaceSocket, workspaceEvent(workspace))
        assertEquals(emptyList(), builds)

        val activeBuild = workspace.latestBuild.copy(
            id = UUID.randomUUID(),
            status = WorkspaceStatus.STARTING,
        )
        listeners.single().onMessage(
            workspaceSocket,
            workspaceEvent(workspace.copy(latestBuild = activeBuild)),
        )
        val log = ProvisionerJobLog(
            id = 1,
            createdAt = Instant.EPOCH,
            source = "provisioner",
            level = "info",
            stage = "Building",
            output = "Applying workspace resources",
        )
        listeners[1].onMessage(buildLogsSocket, moshi.adapter(ProvisionerJobLog::class.java).toJson(log))

        assertEquals(listOf(activeBuild), builds)
        assertEquals(listOf("Applying workspace resources"), output)
        assertEquals(emptyList(), failures)
        assertEquals(
            "https://coder.example.com/api/v2/workspaces/${workspace.id}/watch-ws",
            requests[0].url.toString(),
        )
        assertEquals(
            "https://coder.example.com/api/v2/workspacebuilds/${activeBuild.id}/logs?follow=true",
            requests[1].url.toString(),
        )

        val completedBuild = activeBuild.copy(status = WorkspaceStatus.RUNNING)
        listeners[0].onMessage(
            workspaceSocket,
            workspaceEvent(workspace.copy(latestBuild = completedBuild)),
        )

        assertFalse(watcher.isActive)
        assertEquals(listOf(activeBuild, completedBuild), builds)
        verify(exactly = 1) { workspaceSocket.close(1000, any()) }
        verify(exactly = 1) { buildLogsSocket.close(1000, any()) }
    }

    @Test
    fun `workspace socket failure makes the watcher unavailable`() {
        val httpClient = mockk<OkHttpClient>()
        val socket = mockk<WebSocket>(relaxed = true)
        val listener = slot<WebSocketListener>()
        every { httpClient.newWebSocket(any(), capture(listener)) } returns socket
        val failure = IllegalStateException("WebSockets blocked")
        val failures = mutableListOf<Throwable>()
        val watcher = WebSocketWorkspaceProgressWatcher(
            httpClient,
            moshi,
            URI.create("https://coder.example.com").toURL(),
            DataGen.workspace("failed-progress"),
            WorkspaceProgressCallbacks({}, {}, failures::add),
        )

        listener.captured.onFailure(socket, failure, null)

        assertFalse(watcher.isActive)
        assertEquals(listOf<Throwable>(failure), failures)
        verify(exactly = 1) { socket.cancel() }
    }

    private fun workspaceEvent(workspace: Workspace): String =
        moshi.adapter(WorkspaceWatchEvent::class.java).toJson(WorkspaceWatchEvent("data", workspace))
}
