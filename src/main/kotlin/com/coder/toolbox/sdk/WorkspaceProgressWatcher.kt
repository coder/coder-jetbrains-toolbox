package com.coder.toolbox.sdk

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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

private val ACTIVE_BUILD_STATUSES = setOf(
    WorkspaceStatus.PENDING,
    WorkspaceStatus.STARTING,
    WorkspaceStatus.STOPPING,
)
private const val NORMAL_CLOSURE = 1000

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
    private val active = AtomicBoolean(true)
    private val lock = Any()
    private val workspaceID = workspace.id
    private val workspaceName = workspace.name
    private val initialBuildID = workspace.latestBuild.id

    private var watchedBuildID: UUID? = null
    private var lastLogID = 0L
    private var workspaceSocket: WebSocket? = null
    private var buildLogsSocket: WebSocket? = null
    private var streamGeneration = 0L
    private var streamAvailable = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    init {
        if (workspaceProgressSupportedViaWebSockets) connect()
    }

    val isActive: Boolean
        get() = active.get()

    suspend fun onWorkspacePolled(workspace: Workspace) {
        if (watchBuild(workspace.latestBuild) && !synchronized(lock) { streamAvailable }) {
            pollBuildLogs(workspace.latestBuild)
        }
    }

    /** Returns whether this snapshot contains an active build whose logs should be followed. */
    private fun watchBuild(build: WorkspaceBuild): Boolean {
        if (!isActive) return false
        if (build.status !in ACTIVE_BUILD_STATUSES) {
            // An action can still be preparing a new build while snapshots report the previous one.
            val awaitingBuild = synchronized(lock) { watchedBuildID == null && build.id == initialBuildID }
            if (!awaitingBuild) {
                onBuild(build)
                close()
            }
            return false
        }
        onBuild(build)

        synchronized(lock) {
            selectBuild(build.id)
            if (streamAvailable && buildLogsSocket == null) {
                openBuildLogsSocket(build.id, streamGeneration)
            }
        }
        return true
    }

    private fun selectBuild(buildID: UUID) {
        if (watchedBuildID == buildID) return
        buildLogsSocket?.cancel()
        buildLogsSocket = null
        watchedBuildID = buildID
        lastLogID = 0L
    }

    private fun connect() {
        val generation = synchronized(lock) {
            if (!isActive) return
            ++streamGeneration
        }
        try {
            val socket = client.streamWorkspace(
                workspaceID,
                onOpen = {
                    synchronized(lock) {
                        if (isActive && generation == streamGeneration) {
                            streamAvailable = true
                            reconnectAttempts = 0
                        }
                    }
                },
                onMessage = { event ->
                    if (isCurrent(generation)) event.data?.latestBuild?.let(::watchBuild)
                },
                onClosed = { code, reason ->
                    streamFailed(generation, IOException("Workspace progress WebSocket closed: $code $reason"))
                },
                onFailure = { error -> streamFailed(generation, error) },
            )
            synchronized(lock) {
                if (isActive && generation == streamGeneration) {
                    workspaceSocket = socket
                } else {
                    socket.cancel()
                }
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            streamFailed(generation, ex)
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        synchronized(lock) { isActive && generation == streamGeneration }

    private fun openBuildLogsSocket(buildID: UUID, generation: Long) {
        try {
            val socket = client.streamWorkspaceBuildLogs(
                buildID,
                onOpen = {
                    synchronized(lock) {
                        if (isActive && generation == streamGeneration) reconnectAttempts = 0
                    }
                },
                onMessage = { log ->
                    if (isCurrent(generation)) emitLogIfNew(log.id, log.output)
                },
                onClosed = { code, reason ->
                    if (code != NORMAL_CLOSURE) {
                        streamFailed(generation, IOException("Workspace build log WebSocket closed: $code $reason"))
                    }
                },
                onFailure = { error -> streamFailed(generation, error) },
            )
            if (isActive && generation == streamGeneration) {
                buildLogsSocket = socket
            } else {
                socket.cancel()
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            streamFailed(generation, ex)
        }
    }

    private suspend fun pollBuildLogs(build: WorkspaceBuild) {
        val latestLog = try {
            client.workspaceBuildLogs(build.id).lastOrNull()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            onFailure(ex, "Failed to retrieve progress for workspace build ${build.id}")
            return
        }
        latestLog?.let { log ->
            val currentLog = synchronized(lock) {
                if (!isActive || log.id < lastLogID) false
                else {
                    lastLogID = log.id
                    true
                }
            }
            if (currentLog) onOutput(log.output)
        }
    }

    private fun emitLogIfNew(logID: Long, output: String) {
        synchronized(lock) {
            if (!isActive || logID <= lastLogID) return
            lastLogID = logID
        }
        onOutput(output)
    }

    private fun streamFailed(generation: Long, error: Throwable) {
        val (sockets, delayMs) = synchronized(lock) {
            if (!isActive || generation != streamGeneration) return
            ++streamGeneration
            streamAvailable = false
            val sockets = workspaceSocket to buildLogsSocket
            workspaceSocket = null
            buildLogsSocket = null
            val delayMs = minOf(30_000L, 1_000L shl reconnectAttempts.coerceAtMost(5))
            reconnectAttempts++
            sockets to delayMs
        }
        sockets.first?.cancel()
        sockets.second?.cancel()
        onFailure(error, "Workspace progress WebSocket failed for $workspaceName; polling will continue")
        val job = scope.launch {
            delay(delayMs)
            connect()
        }
        synchronized(lock) {
            if (isActive && streamGeneration == generation + 1) {
                reconnectJob?.cancel()
                reconnectJob = job
            } else {
                job.cancel()
            }
        }
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        val (sockets, retry) = synchronized(lock) {
            ++streamGeneration
            streamAvailable = false
            val sockets = workspaceSocket to buildLogsSocket
            workspaceSocket = null
            buildLogsSocket = null
            val retry = reconnectJob
            reconnectJob = null
            sockets to retry
        }
        retry?.cancel()
        sockets.first?.close(NORMAL_CLOSURE, "Workspace progress watch finished")
        sockets.second?.close(NORMAL_CLOSURE, "Workspace build finished")
    }
}
