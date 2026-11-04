package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.IN_PROGRESS_BUILD_STATUSES
import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import okhttp3.WebSocket
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val NORMAL_CLOSURE = 1000
private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
private const val MAX_RECONNECT_DELAY_MS = 30_000L

/**
 * Provides CLI preparation progress, then build progress through WebSockets with REST polling fallback.
 *
 * Lifecycle states:
 * - [State.AwaitingBuild]: no build has been selected from a snapshot. The previous completed build,
 *   if any, is ignored while an action prepares a new build. When the initial build is already active,
 *   there is no completed build to ignore, so even a first snapshot that is terminal finishes the watch.
 * - [State.FollowingBuild]: a [BuildSession] owns the selected build's log stream, last emitted log ID,
 *   and optional REST request. Its [LogMode] is either [LogMode.FALLBACK] or [LogMode.STREAMING].
 * - [State.Finished]: the build session has been cancelled and further events are ignored.
 *
 * Events:
 * - [Event.CliOutput] carries the latest nonblank CLI progress line while preparing a build.
 * - [Event.Snapshot] carries a build from the workspace stream or a provider poll. Polled snapshots
 *   are conflated and delivered without waiting for progress processing or a REST request.
 * - [Event.LogStreamUpdate] carries any build-log stream activity: opening, message, failure, or
 *   normal completion (null). Only a failure reports an error; normal completion enables REST fallback.
 * - [Event.PollResult] carries the current session's REST result, whether successful or failed.
 * - [Event.WorkspaceFailure] reports only workspace-stream failures, without changing the build lifecycle.
 * - [Event.Stop] finishes the lifecycle when the watch coroutine exits, including cancellation.
 *
 * Transitions:
 * - AwaitingBuild + CLI output -> remain AwaitingBuild and publish the preparation message.
 * - FollowingBuild/Finished + CLI output -> ignore it; build progress owns the description.
 * - AwaitingBuild + terminal snapshot of the previous completed build -> AwaitingBuild, with no output.
 * - AwaitingBuild + active snapshot -> FollowingBuild in FALLBACK, starting its log stream if supported.
 * - AwaitingBuild + any other terminal snapshot -> Finished, after publishing the completed build.
 * - FollowingBuild + active snapshot of the same build -> retain the session and publish the build.
 * - FollowingBuild + active snapshot of another build -> cancel the old session and start a fresh one.
 * - FollowingBuild + terminal snapshot -> publish the completed build and enter Finished.
 * - FollowingBuild + log opening/message -> STREAMING; messages advance the session's log cursor.
 * - FollowingBuild + log failure/completion -> FALLBACK. Socket flows retry failures independently;
 *   a normal log close does not finish the build or prevent later polling.
 * - FollowingBuild in FALLBACK + polled snapshot -> start one REST request if none is pending.
 * - FollowingBuild + REST result -> clear the request and publish current output or report its failure.
 * - Any state + Stop -> Finished. Leaving FollowingBuild always cancels its stream and REST request.
 *
 * One coroutine consumes events and owns the state machine. Only the current session's channels and
 * request participate in selection, so replaced sessions cannot publish late results. A REST request
 * may finish after streaming resumes; both sources share the same log cursor to reject older output.
 * The emission lock makes [close] a synchronous callback barrier; cancellation then drives Stop and
 * resource cleanup in the coroutine. AwaitingBuild deliberately has no preparation deadline.
 * A progress callback exception is reported before cancelling this watcher; a failure callback
 * exception cancels it without another notification. Neither propagates to the owning scope.
 * Cancellation exceptions retain their normal coroutine behavior.
 */
internal class WorkspaceProgressWatcher(
    workspace: Workspace,
    workspaceProgressSupportedViaWebSockets: Boolean,
    scope: CoroutineScope,
    private val client: CoderRestClient,
    private val onBuild: (WorkspaceBuild) -> Unit,
    private val onOutput: (String) -> Unit,
    private val onFailure: (Throwable, String) -> Unit,
) : AutoCloseable {
    private val workspaceID = workspace.id
    private val workspaceName = workspace.name

    // Only callback delivery and shutdown share a lock. The watch coroutine owns progress state.
    private val emissionLock = Any()

    @Volatile
    private var closed = false
    private val polls = Channel<WorkspaceBuild>(Channel.CONFLATED)
    private val cliOutput = Channel<String>(Channel.CONFLATED)
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        watch(workspace.latestBuild, workspaceProgressSupportedViaWebSockets)
    }

    init {
        job.invokeOnCompletion {
            synchronized(emissionLock) { closed = true }
            polls.cancel()
            cliOutput.cancel()
        }
        job.start()
    }

    val isActive: Boolean
        get() = !closed

    fun onWorkspacePolled(workspace: Workspace) {
        // Progress requests must not hold up the provider's refresh of other workspaces.
        // If several snapshots arrive before consumption, only the latest one is needed.
        polls.trySend(workspace.latestBuild)
    }

    fun onCliOutput(output: String) {
        if (output.isNotBlank()) cliOutput.trySend(output)
    }

    override fun close() {
        synchronized(emissionLock) { closed = true }
        job.cancel()
    }

    private inline fun publish(action: () -> Unit) {
        synchronized(emissionLock) {
            if (closed) return
            try {
                action()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                use {
                    reportFailure(ex, "Failed to publish workspace progress for $workspaceName")
                }
            }
        }
    }

    private fun reportFailure(error: Throwable, message: String) {
        synchronized(emissionLock) {
            if (closed) return
            try {
                onFailure(error, message)
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                // The failure callback is also caller-owned; retrying it could recurse indefinitely.
                close()
            }
        }
    }

    private suspend fun watch(initialBuild: WorkspaceBuild, webSocketsEnabled: Boolean) = coroutineScope {
        val machine = StateMachine(initialBuild, webSocketsEnabled, this)
        var workspaceEvents = if (webSocketsEnabled) workspaceEvents().produceIn(this) else null
        try {
            while (!closed) {
                val session = machine.session
                select {
                    workspaceEvents?.onReceiveCatching { result ->
                        when (val event = result.getOrNull()) {
                            null -> workspaceEvents = null
                            is SocketEvent.Message -> machine.handle(Event.Snapshot(event.value, SnapshotSource.STREAM))
                            is SocketEvent.Failed -> machine.handle(Event.WorkspaceFailure(event))
                            SocketEvent.Opened -> Unit
                        }
                    }
                    session?.logs?.onReceiveCatching { result ->
                        machine.handle(Event.LogStreamUpdate(result.getOrNull()))
                    }
                    polls.onReceive { machine.handle(Event.Snapshot(it, SnapshotSource.POLL)) }
                    session?.pendingLog?.onAwait { machine.handle(Event.PollResult(it)) }
                    cliOutput.onReceive { machine.handle(Event.CliOutput(it)) }
                }
            }
        } finally {
            machine.handle(Event.Stop)
        }
    }

    private inner class StateMachine(
        initialBuild: WorkspaceBuild,
        private val webSocketsEnabled: Boolean,
        private val scope: CoroutineScope,
    ) {
        private var state: State = State.AwaitingBuild(
            initialBuild.takeUnless { it.status in IN_PROGRESS_BUILD_STATUSES }?.id,
        )
        val session: BuildSession?
            get() = (state as? State.FollowingBuild)?.session

        fun handle(event: Event) {
            if (state == State.Finished) return
            when (event) {
                is Event.CliOutput -> if (state is State.AwaitingBuild) publish { onOutput(event.output) }
                is Event.Snapshot -> onSnapshot(event)
                is Event.LogStreamUpdate -> session?.let { onLogStreamUpdate(it, event.value) }
                is Event.PollResult -> session?.let { onPollResult(it, event.value) }
                is Event.WorkspaceFailure -> reportFailure(event.failure.error, event.failure.message)
                Event.Stop -> transitionTo(State.Finished)
            }
        }

        private fun transitionTo(next: State) {
            session?.close()
            state = next
            when (next) {
                is State.FollowingBuild -> {
                    next.session.logs = if (webSocketsEnabled) {
                        buildLogEvents(next.session.id).produceIn(scope)
                    } else null
                }

                State.Finished -> this@WorkspaceProgressWatcher.close()
                is State.AwaitingBuild -> Unit
            }
        }

        private fun onSnapshot(event: Event.Snapshot) {
            val build = event.build
            if (build.status !in IN_PROGRESS_BUILD_STATUSES) {
                if ((state as? State.AwaitingBuild)?.previousCompletedBuildID == build.id) return
                publish { onBuild(build) }
                transitionTo(State.Finished)
                return
            }
            val following = session?.takeIf { it.id == build.id } ?: BuildSession(build.id).also {
                transitionTo(State.FollowingBuild(it))
            }
            publish { onBuild(build) }
            if (!closed && event.source == SnapshotSource.POLL && following.canPoll) {
                following.pendingLog = scope.async {
                    runCatching { client.workspaceBuildLogs(build.id).lastOrNull() }
                        .onFailure { if (it is CancellationException) throw it }
                }
            }
        }

        private fun onLogStreamUpdate(session: BuildSession, event: SocketEvent<ProvisionerJobLog>?) {
            when (event) {
                null -> {
                    session.logMode = LogMode.FALLBACK
                    session.logs = null
                }

                is SocketEvent.Message -> {
                    session.logMode = LogMode.STREAMING
                    emitLog(session, event.value)
                }

                is SocketEvent.Failed -> {
                    session.logMode = LogMode.FALLBACK
                    reportFailure(event.error, event.message)
                }

                SocketEvent.Opened -> session.logMode = LogMode.STREAMING
            }
        }

        private fun onPollResult(session: BuildSession, result: Result<ProvisionerJobLog?>) {
            session.pendingLog = null
            result.fold(
                onSuccess = { log ->
                    // Polling refreshes the description, so repeat the latest log when unchanged.
                    log?.let { emitLog(session, it, allowRepeat = true) }
                },
                onFailure = { error ->
                    reportFailure(error, "Failed to retrieve progress for workspace build ${session.id}")
                },
            )
        }

        private fun emitLog(session: BuildSession, log: ProvisionerJobLog, allowRepeat: Boolean = false) {
            val previousID = session.lastLogID
            if (previousID != null && log.id < previousID) return
            if (log.id == previousID && !allowRepeat) return
            session.lastLogID = log.id
            publish { onOutput(log.output) }
        }
    }

    private fun workspaceEvents(): Flow<SocketEvent<WorkspaceBuild>> = socketEvents(
        "Workspace progress WebSocket",
        normalClosureCompletes = false,
    ) { onOpen, onMessage, onClosed, onFailure ->
        client.streamWorkspace(workspaceID, onOpen, { it.data?.latestBuild?.let(onMessage) }, onClosed, onFailure)
    }

    private fun buildLogEvents(buildID: UUID): Flow<SocketEvent<ProvisionerJobLog>> = socketEvents(
        "Workspace build log WebSocket",
        normalClosureCompletes = true,
    ) { onOpen, onMessage, onClosed, onFailure ->
        client.streamWorkspaceBuildLogs(buildID, onOpen, onMessage, onClosed, onFailure)
    }

    private fun <T> socketEvents(
        description: String,
        normalClosureCompletes: Boolean,
        open: OpenProgressSocket<T>,
    ): Flow<SocketEvent<T>> = flow {
        var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        while (true) {
            try {
                callbackFlow {
                    val socket = open(
                        { trySend(SocketEvent.Opened) },
                        { trySend(SocketEvent.Message(it)) },
                        { code, reason ->
                            if (normalClosureCompletes && code == NORMAL_CLOSURE) close()
                            else close(IOException("$description closed: $code $reason"))
                        },
                        { close(it) },
                    )
                    awaitClose { socket.cancel() }
                }
                    // Socket callbacks never block or drop lifecycle events when the consumer is busy.
                    .buffer(Channel.UNLIMITED)
                    .collect { event ->
                        // Accepting a connection alone must not reset retries for an endpoint that keeps dropping it.
                        if (event is SocketEvent.Message) reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                        emit(event)
                    }
                return@flow
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                emit(SocketEvent.Failed(ex, "$description failed for $workspaceName; polling will continue"))
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
    }

    private sealed interface State {
        data class AwaitingBuild(val previousCompletedBuildID: UUID?) : State
        data class FollowingBuild(val session: BuildSession) : State
        data object Finished : State
    }

    private class BuildSession(val id: UUID) : AutoCloseable {
        var logMode = LogMode.FALLBACK
        var logs: ReceiveChannel<SocketEvent<ProvisionerJobLog>>? = null
        var lastLogID: Long? = null
        var pendingLog: Deferred<Result<ProvisionerJobLog?>>? = null
        val canPoll: Boolean
            get() = logMode == LogMode.FALLBACK && pendingLog == null

        override fun close() {
            logs?.cancel()
            pendingLog?.cancel()
        }
    }

    private enum class LogMode { FALLBACK, STREAMING }

    private enum class SnapshotSource { STREAM, POLL }

    private sealed interface Event {
        data class CliOutput(val output: String) : Event
        data class Snapshot(val build: WorkspaceBuild, val source: SnapshotSource) : Event
        data class LogStreamUpdate(val value: SocketEvent<ProvisionerJobLog>?) : Event
        data class PollResult(val value: Result<ProvisionerJobLog?>) : Event
        data class WorkspaceFailure(val failure: SocketEvent.Failed) : Event
        data object Stop : Event
    }

    private typealias OpenProgressSocket<T> = (
        onOpen: () -> Unit,
        onMessage: (T) -> Unit,
        onClosed: (Int, String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) -> WebSocket

    private sealed interface SocketEvent<out T> {
        data object Opened : SocketEvent<Nothing>
        data class Message<T>(val value: T) : SocketEvent<T>
        data class Failed(val error: Throwable, val message: String) : SocketEvent<Nothing>
    }
}
