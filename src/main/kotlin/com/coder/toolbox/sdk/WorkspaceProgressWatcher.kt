package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private val ACTIVE_BUILD_STATUSES = setOf(
    WorkspaceStatus.PENDING,
    WorkspaceStatus.STARTING,
    WorkspaceStatus.STOPPING,
)
private const val NORMAL_CLOSURE = 1000
private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
private const val MAX_RECONNECT_DELAY_MS = 30_000L

/** Provides build progress through WebSockets, with REST polling while a stream is unavailable. */
internal class WorkspaceProgressWatcher(
    workspace: Workspace,
    private val client: CoderRestClient,
    workspaceProgressSupportedViaWebSockets: Boolean,
    private val scope: CoroutineScope,
    private val onBuild: (WorkspaceBuild) -> Unit,
    private val onOutput: (String) -> Unit,
    private val onFailure: (Throwable, String) -> Unit,
) : AutoCloseable {
    private val workspaceID = workspace.id
    private val workspaceName = workspace.name
    private val initialBuildID = workspace.latestBuild.id

    // All state changes and callbacks run under this lock; REST requests run outside it.
    private val lock = Any()
    private var active = true
    private var watchedBuild: BuildProgress? = null
    private var connection: Connection? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var reconnectJob: Job? = null

    init {
        if (workspaceProgressSupportedViaWebSockets) connect()
    }

    val isActive: Boolean
        get() = synchronized(lock) { active }

    suspend fun onWorkspacePolled(workspace: Workspace) {
        val build = synchronized(lock) {
            val build = updateBuild(workspace.latestBuild) ?: return
            if (connection?.isOpen == true) return
            build
        }
        pollBuildLogs(build)
    }

    override fun close() {
        synchronized(lock) {
            if (!active) return
            active = false
            reconnectJob?.cancel()
            reconnectJob = null
            connection?.close()
            connection = null
        }
    }

    /** Selects an active build, or finishes the watch once the expected build completes. */
    private fun updateBuild(build: WorkspaceBuild): BuildProgress? {
        if (!active) return null
        if (build.status !in ACTIVE_BUILD_STATUSES) {
            // An action can still be preparing a new build while snapshots report the previous one.
            if (watchedBuild != null || build.id != initialBuildID) {
                onBuild(build)
                close()
            }
            return null
        }

        onBuild(build)
        if (!active) return null
        val progress = watchedBuild?.takeIf { it.id == build.id } ?: BuildProgress(build.id).also {
            watchedBuild = it
            connection?.cancelBuildLogs()
        }
        connection?.takeIf { it.isOpen }?.watchBuildLogs(progress)
        return progress
    }

    private suspend fun pollBuildLogs(build: BuildProgress) {
        val latestLog = try {
            client.workspaceBuildLogs(build.id).lastOrNull()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            synchronized(lock) {
                if (active && watchedBuild === build) {
                    onFailure(ex, "Failed to retrieve progress for workspace build ${build.id}")
                }
            }
            return
        }
        synchronized(lock) {
            // Polling refreshes the description, so re-emit an unchanged latest log as well.
            latestLog?.let { emitLog(build, it, allowRepeat = true) }
        }
    }

    private fun emitLog(build: BuildProgress, log: ProvisionerJobLog, allowRepeat: Boolean = false) {
        if (!active || watchedBuild !== build) return
        if (log.id < build.lastLogID || (log.id == build.lastLogID && !allowRepeat)) return
        build.lastLogID = log.id
        onOutput(log.output)
    }

    private fun connect() {
        synchronized(lock) {
            if (!active) return
            reconnectJob = null
            val nextConnection = Connection()
            connection = nextConnection
            nextConnection.open()
        }
    }

    private class BuildProgress(val id: UUID, var lastLogID: Long = 0L)

    /** Owns one connection attempt. Replacing it makes all of its late callbacks harmless. */
    private inner class Connection {
        var isOpen = false
            private set
        private var workspaceSocket: WebSocket? = null
        private var buildLogsSocket: WebSocket? = null

        fun open() {
            workspaceSocket = openSocket {
                client.streamWorkspace(
                    workspaceID,
                    onOpen = {
                        onEvent {
                            isOpen = true
                            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                            watchedBuild?.let(::watchBuildLogs)
                        }
                    },
                    onMessage = { event ->
                        onEvent { event.data?.latestBuild?.let(::updateBuild) }
                    },
                    onClosed = { code, reason ->
                        fail(IOException("Workspace progress WebSocket closed: $code $reason"))
                    },
                    onFailure = ::fail,
                )
            }
        }

        fun watchBuildLogs(build: BuildProgress) {
            if (buildLogsSocket != null) return
            buildLogsSocket = openSocket(build) {
                client.streamWorkspaceBuildLogs(
                    build.id,
                    onOpen = { onEvent(build) { reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS } },
                    onMessage = { log -> onEvent(build) { emitLog(build, log) } },
                    onClosed = { code, reason ->
                        onEvent(build) {
                            // A normal close means the logs ended; the workspace stream reports completion.
                            if (code != NORMAL_CLOSURE) {
                                fail(IOException("Workspace build log WebSocket closed: $code $reason"))
                            }
                        }
                    },
                    onFailure = { error -> onEvent(build) { fail(error) } },
                )
            }
        }

        fun cancelBuildLogs() {
            buildLogsSocket?.cancel()
            buildLogsSocket = null
        }

        fun close() {
            workspaceSocket?.close(NORMAL_CLOSURE, "Workspace progress watch finished")
            buildLogsSocket?.close(NORMAL_CLOSURE, "Workspace build finished")
        }

        private fun isCurrent(build: BuildProgress? = null): Boolean =
            active && connection === this && (build == null || watchedBuild === build)

        private inline fun onEvent(build: BuildProgress? = null, action: () -> Unit) {
            synchronized(lock) {
                if (isCurrent(build)) action()
            }
        }

        private inline fun openSocket(build: BuildProgress? = null, open: () -> WebSocket): WebSocket? {
            return try {
                val socket = open()
                // A client can report failure before returning the socket.
                if (isCurrent(build)) socket else {
                    socket.cancel()
                    null
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                onEvent(build) { fail(ex) }
                null
            }
        }

        private fun fail(error: Throwable) = onEvent {
            connection = null
            workspaceSocket?.cancel()
            buildLogsSocket?.cancel()
            val delayMs = reconnectDelayMs
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            reconnectJob = scope.launch {
                delay(delayMs)
                connect()
            }
            onFailure(error, "Workspace progress WebSocket failed for $workspaceName; polling will continue")
        }
    }
}
