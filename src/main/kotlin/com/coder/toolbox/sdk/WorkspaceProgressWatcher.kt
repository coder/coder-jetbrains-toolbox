package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val ACTIVE_BUILD_STATUSES = setOf(
    WorkspaceStatus.PENDING,
    WorkspaceStatus.STARTING,
    WorkspaceStatus.STOPPING,
)
private const val NORMAL_CLOSURE = 1000

internal interface WorkspaceProgressWatcher : AutoCloseable {
    val isActive: Boolean

    fun watchBuild(build: WorkspaceBuild)
}

internal data class WorkspaceProgressCallbacks(
    val onBuild: (WorkspaceBuild) -> Unit,
    val onOutput: (String) -> Unit,
    val onFailure: (Throwable) -> Unit,
)

internal class WebSocketWorkspaceProgressWatcher(
    private val httpClient: OkHttpClient,
    moshi: Moshi,
    private val baseUrl: URL,
    workspace: Workspace,
    private val callbacks: WorkspaceProgressCallbacks,
) : WorkspaceProgressWatcher {
    private val active = AtomicBoolean(true)
    private val lock = Any()
    private val workspaceAdapter = moshi.adapter(WorkspaceWatchEvent::class.java)
    private val logAdapter = moshi.adapter(ProvisionerJobLog::class.java)
    private val initialBuildID = workspace.latestBuild.id

    private var watchedBuildID: UUID? = null
    private var lastLogID = 0L
    private var buildLogsSocket: WebSocket? = null

    private val workspaceSocket = httpClient.newWebSocket(
        Request.Builder()
            .url(baseUrl.webSocketUrl("/api/v2/workspaces/${workspace.id}/watch-ws"))
            .build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!active.get()) return
                runCatching {
                    workspaceAdapter.fromJson(text)
                }.onFailure(::fail).getOrNull()?.data?.let { updatedWorkspace ->
                    val build = updatedWorkspace.latestBuild
                    val currentBuildID = synchronized(lock) { watchedBuildID }
                    if (currentBuildID != null || build.id != initialBuildID) {
                        watchBuild(build)
                        if (build.status !in ACTIVE_BUILD_STATUSES) close()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (active.get()) {
                    fail(IOException("Workspace progress WebSocket closed: $code $reason"))
                }
            }
        },
    )

    override val isActive: Boolean
        get() = active.get()

    override fun watchBuild(build: WorkspaceBuild) {
        if (active.get()) {
            callbacks.onBuild(build)
            if (build.status in ACTIVE_BUILD_STATUSES) {
                synchronized(lock) {
                    if (watchedBuildID != build.id || buildLogsSocket == null) {
                        buildLogsSocket?.cancel()
                        watchedBuildID = build.id
                        lastLogID = 0
                        buildLogsSocket = openBuildLogsSocket(build.id)
                    }
                }
            }
        }
    }

    private fun openBuildLogsSocket(buildID: UUID): WebSocket = httpClient.newWebSocket(
        Request.Builder()
            .url(baseUrl.webSocketUrl("/api/v2/workspacebuilds/$buildID/logs?follow=true"))
            .build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!active.get()) return
                runCatching {
                    logAdapter.fromJson(text)
                }.onFailure(::fail).getOrNull()?.let { log ->
                    val isNew = synchronized(lock) {
                        if (log.id <= lastLogID) {
                            false
                        } else {
                            lastLogID = log.id
                            true
                        }
                    }
                    if (isNew) callbacks.onOutput(log.output)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t)
            }
        },
    )

    private fun fail(error: Throwable) {
        if (!active.compareAndSet(true, false)) return
        workspaceSocket.cancel()
        synchronized(lock) {
            buildLogsSocket?.cancel()
            buildLogsSocket = null
        }
        callbacks.onFailure(error)
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        workspaceSocket.close(NORMAL_CLOSURE, "Workspace progress watch finished")
        synchronized(lock) {
            buildLogsSocket?.close(NORMAL_CLOSURE, "Workspace build finished")
            buildLogsSocket = null
        }
    }
}

@JsonClass(generateAdapter = true)
internal data class WorkspaceWatchEvent(
    @property:Json(name = "type") val type: String,
    @property:Json(name = "data") val data: Workspace? = null,
)

private fun URL.webSocketUrl(pathAndQuery: String): String {
    val scheme = if (protocol == "https") "wss" else "ws"
    val portPart = if (port == -1) "" else ":$port"
    val path = if (pathAndQuery.startsWith('/')) pathAndQuery else "/$pathAndQuery"
    return "$scheme://$host$portPart$path"
}
