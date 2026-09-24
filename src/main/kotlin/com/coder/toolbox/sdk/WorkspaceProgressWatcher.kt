package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import okhttp3.WebSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val ACTIVE_BUILD_STATUSES = setOf(
    WorkspaceStatus.PENDING,
    WorkspaceStatus.STARTING,
    WorkspaceStatus.STOPPING,
)
private const val NORMAL_CLOSURE = 1000

internal class WorkspaceProgressWatcher(
    workspace: Workspace,
    private val client: CoderRestClient,
    private val onBuild: (WorkspaceBuild) -> Unit,
    private val onOutput: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val lock = Any()
    private val initialBuildID = workspace.latestBuild.id

    private var watchedBuildID: UUID? = null
    private var lastLogID = 0L
    private var buildLogsSocket: WebSocket? = null

    private val workspaceSocket = client.streamWorkspace(
        workspace.id,
        onMessage = { event ->
            if (active.get()) {
                event.data?.let { updatedWorkspace ->
                    handleWorkspace(updatedWorkspace)
                }
            }
        },
        onFailure = ::fail,
        onClosed = { code, reason ->
            if (active.get()) {
                fail(IOException("Workspace progress WebSocket closed: $code $reason"))
            }
        },
    )

    val isActive: Boolean
        get() = active.get()

    fun watchBuild(build: WorkspaceBuild) {
        if (active.get()) {
            onBuild(build)
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

    private fun handleWorkspace(updatedWorkspace: Workspace) {
        val build = updatedWorkspace.latestBuild
        val currentBuildID = synchronized(lock) { watchedBuildID }
        if (currentBuildID != null || build.id != initialBuildID) {
            watchBuild(build)
            if (build.status !in ACTIVE_BUILD_STATUSES) close()
        }
    }

    private fun openBuildLogsSocket(buildID: UUID): WebSocket = client.streamWorkspaceBuildLogs(
        buildID,
        onMessage = { log ->
            if (active.get()) {
                val isNew = synchronized(lock) {
                    if (log.id <= lastLogID) {
                        false
                    } else {
                        lastLogID = log.id
                        true
                    }
                }
                if (isNew) onOutput(log.output)
            }
        },
        onFailure = ::fail,
        onClosed = { _, _ -> },
    )

    private fun fail(error: Throwable) {
        if (!active.compareAndSet(true, false)) return
        workspaceSocket.cancel()
        synchronized(lock) {
            buildLogsSocket?.cancel()
            buildLogsSocket = null
        }
        onFailure(error)
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
