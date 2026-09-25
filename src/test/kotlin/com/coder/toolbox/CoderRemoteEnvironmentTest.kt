package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.Features
import com.coder.toolbox.diagnostics.CoderLogger
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.sdk.v2.models.ProvisionerJobLog
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceWatchEvent
import com.coder.toolbox.session.SessionId
import com.coder.toolbox.session.SessionIdRegistry
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.views.Action
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.environments.SshEnvironmentContentsView
import com.jetbrains.toolbox.api.remoteDev.states.CustomRemoteEnvironmentStateV2
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentDescription
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoderRemoteEnvironmentTest {
    @Test
    fun `diagnostic collector uses current workspace agent and CLI after refresh`() = runTest {
        val fixture = fixture(backgroundScope)
        val collector = fixture.environment.diagnosticInfoCollector
        val refreshedCli = mockk<CoderCLIManager>(relaxed = true)
        val updatedAgent = fixture.agent.copy(name = "updated-agent")
        fixture.environment.update(
            fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING)),
            updatedAgent,
        )
        fixture.environment.updateClientAndCli(mockk(relaxed = true), refreshedCli)
        val root = Files.createTempDirectory("coder-diagnostics-test")
        try {
            coEvery { refreshedCli.supportBundle(any(), any()) } answers {
                Files.writeString(secondArg(), "bundle")
                Unit
            }
            collector.collectAdditionalDiagnostics(root)
            coVerify(exactly = 1) {
                refreshedCli.supportBundle(
                    match {
                        it.ownerAndWsName == "${fixture.workspace.ownerName}/${fixture.workspace.name}" &&
                                it.agentName == "updated-agent"
                    },
                    root.resolve("coder-support.zip"),
                )
            }
            fixture.environment.update(fixture.workspace, null)
            collector.collectAdditionalDiagnostics(root)
            coVerify(exactly = 1) { refreshedCli.supportBundle(match { it.agentName == null }, any()) }
        } finally {
            root.toFile().deleteRecursively()
            fixture.environment.dispose()
        }
    }

    @Test
    fun `auto-connect requests SSH while the environment is initialized`() = runTest {
        val fixture = fixture(backgroundScope, autoConnect = true)

        try {
            assertTrue(fixture.environment.connectionRequest.value)
            assertNull(fixture.currentSessionId())
            verify(exactly = 1) {
                fixture.logger.info(
                    "Auto-connect is enabled for ${fixture.environment.id}, trying to establish SSH connection"
                )
            }
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    fun `requesting an SSH connection does not create its session`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.startSshConnection()

            assertTrue(fixture.environment.connectionRequest.value)
            assertNull(fixture.currentSessionId())
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    fun `SSH connection info exports the session activated by the connection callback`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            val contentsView = fixture.environment.getContentsView() as SshEnvironmentContentsView
            assertNull(contentsView.getConnectionInfo().environment)
            assertNull(fixture.currentSessionId())

            fixture.environment.beforeConnection()
            val sessionId = assertNotNull(fixture.currentSessionId())
            val connectionInfo = contentsView.getConnectionInfo()

            assertEquals(
                mapOf("CODER_TRACE_SESSION_ID" to sessionId.value),
                connectionInfo.environment,
            )
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, sessionId) && isSessionStartedMessage(it)
                })
            }
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    fun `non-manual disconnect retains the session for reconnect`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.beforeConnection()
            val firstSessionId = assertNotNull(fixture.currentSessionId())

            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, firstSessionId) && isSessionStartedMessage(it)
                })
            }

            fixture.environment.afterDisconnect(isManual = false)
            assertEquals(firstSessionId, fixture.currentSessionId())
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, firstSessionId) &&
                            it.contains("without an explicit user disconnect") &&
                            it.contains("environment=Ready") &&
                            it.contains("workspace=RUNNING") &&
                            it.contains("agent=CONNECTED") &&
                            it.contains("agentLifecycle=READY") &&
                            !it.contains("may indicate a workspace or agent change")
                })
            }

            fixture.environment.beforeConnection()
            assertEquals(firstSessionId, fixture.currentSessionId())
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, firstSessionId) && isSessionStartedMessage(it)
                })
            }
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `repeated connection callbacks replace the network metrics poller`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.beforeConnection()
            fixture.environment.beforeConnection()
            runCurrent()

            val sessionId = assertNotNull(fixture.currentSessionId())
            assertEquals(
                1,
                backgroundScope.coroutineContext[Job]?.children?.count { it.isActive },
            )
            verify(exactly = 1) {
                fixture.logger.info(
                    "client_session_id=$sessionId Starting the network metrics poll job for ${fixture.environment.id}",
                )
            }
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    fun `reconnecting after a manual disconnect creates a new session`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.beforeConnection()
            val firstSessionId = assertNotNull(fixture.currentSessionId())

            fixture.environment.afterDisconnect(isManual = true)
            assertNull(fixture.currentSessionId())

            fixture.environment.beforeConnection()
            val secondSessionId = assertNotNull(fixture.currentSessionId())

            assertNotEquals(firstSessionId, secondSessionId)
            verify(exactly = 1) {
                fixture.settingsStore.updateAutoConnect(fixture.environment.id, false)
            }
            verify(exactly = 1) {
                fixture.logger.info(
                    "client_session_id=$firstSessionId Removed Toolbox SSH session for " +
                            "${fixture.environment.id} after manual disconnect",
                )
            }
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, firstSessionId) &&
                            it.contains("after an explicit user disconnect") &&
                            it.contains("Latest known Coder state")
                })
            }
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, firstSessionId) && isSessionStartedMessage(it)
                })
            }
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, secondSessionId) && isSessionStartedMessage(it)
                })
            }
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    fun `non-manual disconnect logs a possible workspace state cause`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.beforeConnection()
            val sessionId = assertNotNull(fixture.currentSessionId())
            val updatedAgent = fixture.agent.copy(
                status = WorkspaceAgentStatus.DISCONNECTED,
                lifecycleState = WorkspaceAgentLifecycleState.SHUTTING_DOWN,
            )
            val updatedWorkspace = fixture.workspace.copy(
                latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING),
            )
            fixture.environment.update(updatedWorkspace, updatedAgent)

            fixture.environment.afterDisconnect(isManual = false)

            assertEquals(sessionId, fixture.currentSessionId())
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, sessionId) &&
                            it.contains("without an explicit user disconnect") &&
                            it.contains("may indicate a workspace or agent change") &&
                            it.contains("environment=Stopping") &&
                            it.contains("workspace=STOPPING") &&
                            it.contains("agent=DISCONNECTED") &&
                            it.contains("agentLifecycle=SHUTTING_DOWN")
                })
            }
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    fun `disposing an environment removes and logs its SSH session once`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.beforeConnection()
            val sessionId = assertNotNull(fixture.currentSessionId())

            fixture.environment.dispose()

            assertNull(fixture.currentSessionId())
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, sessionId) && isSessionDisposedMessage(it)
                })
            }

            fixture.environment.dispose()
            assertNull(fixture.currentSessionId())
            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, sessionId) && isSessionDisposedMessage(it)
                })
            }
        } finally {
            fixture.removeSession()
        }
    }

    @Test
    fun `workspace and agent status logs include the old and new values`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.beforeConnection()
            val sessionId = assertNotNull(fixture.currentSessionId())
            val updatedAgent = fixture.agent.copy(
                status = WorkspaceAgentStatus.DISCONNECTED,
                lifecycleState = WorkspaceAgentLifecycleState.SHUTTING_DOWN,
            )
            val updatedWorkspace = fixture.workspace.copy(
                latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING),
            )

            fixture.environment.update(updatedWorkspace, updatedAgent)

            verify(exactly = 1) {
                fixture.logger.info(match<String> {
                    hasSessionId(it, sessionId) &&
                            it.contains("changed from Ready to Stopping") &&
                            it.contains("Workspace status: RUNNING -> STOPPING") &&
                            it.contains("agent status: CONNECTED -> DISCONNECTED") &&
                            it.contains("agent lifecycle state: READY -> SHUTTING_DOWN")
                })
            }
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `start action shows initial progress while the CLI runs`() = runTest {
        val fixture = fixture(
            this,
            workspaceStatus = WorkspaceStatus.STOPPED,
            workspaceProgressWebSockets = true,
        )
        val workspaceMessage = slot<(WorkspaceWatchEvent) -> Unit>()
        val workspaceOpen = slot<() -> Unit>()
        val buildLogMessage = slot<(ProvisionerJobLog) -> Unit>()
        every {
            fixture.client.streamWorkspace(any(), capture(workspaceOpen), capture(workspaceMessage), any(), any())
        } returns mockk<WebSocket>(relaxed = true)
        every {
            fixture.client.streamWorkspaceBuildLogs(any(), any(), capture(buildLogMessage), any(), any())
        } returns mockk<WebSocket>(relaxed = true)
        val commandStarted = CountDownLatch(1)
        val finishCommand = CountDownLatch(1)
        every { fixture.cli.startWorkspace(any(), any()) } answers {
            commandStarted.countDown()
            check(finishCommand.await(5, TimeUnit.SECONDS))
            ""
        }

        fixture.action("Start").run()
        runCurrent()

        assertTrue(commandStarted.await(5, TimeUnit.SECONDS))
        workspaceOpen.captured()
        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertTrue(progress.indeterminate)
        assertSame(fixture.localizedStrings.getValue("Starting workspace…"), progress.description)

        // A poll can still see the old completed build while the CLI prepares Start.
        fixture.environment.update(fixture.workspace, null)
        val newBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID(), status = WorkspaceStatus.PENDING)
        workspaceMessage.captured(WorkspaceWatchEvent("data", fixture.workspace.copy(latestBuild = newBuild)))
        buildLogMessage.captured(buildLog(1, "Provisioning workspace"))

        val streamedProgress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(fixture.localizedStrings.getValue("Provisioning workspace"), streamedProgress.description)
        val queuedState = assertIs<CustomRemoteEnvironmentStateV2>(fixture.environment.state.value)
        assertSame(fixture.localizedStrings.getValue("Queued"), queuedState.label)

        finishCommand.countDown()
        advanceUntilIdle()
        verify(exactly = 1) { fixture.cli.startWorkspace(any(), any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `update and restart shows request progress followed by build progress`() = runTest {
        val fixture = fixture(this, outdated = true)
        val requestStarted = CompletableDeferred<Unit>()
        val finishRequest = CompletableDeferred<Unit>()
        coEvery { fixture.client.updateWorkspace(any()) } coAnswers {
            requestStarted.complete(Unit)
            finishRequest.await()
            fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STARTING)
        }

        fixture.action("Update and restart").run()
        runCurrent()

        assertTrue(requestStarted.isCompleted)
        val queuedState = assertIs<CustomRemoteEnvironmentStateV2>(fixture.environment.state.value)
        assertSame(fixture.localizedStrings.getValue("Queued"), queuedState.label)
        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertTrue(progress.indeterminate)
        assertSame(fixture.localizedStrings.getValue("Updating and restarting workspace…"), progress.description)

        finishRequest.complete(Unit)
        advanceUntilIdle()
        val awaitingBuild = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(fixture.localizedStrings.getValue("Updating and restarting workspace…"), awaitingBuild.description)

        fixture.environment.update(
            fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STARTING)),
            fixture.agent,
        )
        val buildProgress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertTrue(buildProgress.indeterminate)
        assertSame(
            fixture.localizedStrings.getValue("Building ${fixture.workspaceName} (starting)…"),
            buildProgress.description,
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `failed build action restores status and closes its watcher`() = runTest {
        val fixture = fixture(backgroundScope, outdated = true, workspaceProgressWebSockets = true)
        val socket = mockk<WebSocket>(relaxed = true)
        every { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) } returns socket
        coEvery { fixture.client.updateWorkspace(any()) } throws IllegalStateException("Build request failed")
        val previousState = fixture.environment.state.value

        fixture.action("Update and restart").run()
        runCurrent()

        assertEquals(previousState, fixture.environment.state.value)
        assertIs<EnvironmentDescription.General>(fixture.environment.description.value)
        verify(exactly = 1) { socket.close(1000, any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancelling a build action cancels its request and closes its watcher`() = runTest {
        val pluginJob = Job(backgroundScope.coroutineContext[Job])
        val pluginScope = CoroutineScope(backgroundScope.coroutineContext + pluginJob)
        val fixture = fixture(pluginScope, outdated = true, workspaceProgressWebSockets = true)
        val socket = mockk<WebSocket>(relaxed = true)
        every { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) } returns socket
        val finishRequest = CompletableDeferred<Unit>()
        val requestFinished = CompletableDeferred<Unit>()
        coEvery { fixture.client.updateWorkspace(any()) } coAnswers {
            try {
                finishRequest.await()
                fixture.workspace.latestBuild
            } finally {
                requestFinished.complete(Unit)
            }
        }
        val previousState = fixture.environment.state.value

        fixture.action("Update and restart").run()
        runCurrent()
        assertFalse(requestFinished.isCompleted)
        pluginJob.children.single().cancel()
        runCurrent()

        assertTrue(requestFinished.isCompleted)
        assertTrue(pluginJob.isActive)
        assertEquals(previousState, fixture.environment.state.value)
        assertIs<EnvironmentDescription.General>(fixture.environment.description.value)
        verify(exactly = 1) { socket.close(1000, any()) }
        pluginJob.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `update and start keeps showing progress after the request finishes`() = runTest {
        val fixture = fixture(this, workspaceStatus = WorkspaceStatus.STOPPED, outdated = true)
        coEvery { fixture.client.updateWorkspace(any()) } returns
                fixture.workspace.latestBuild.copy(status = WorkspaceStatus.PENDING)

        fixture.action("Update and start").run()
        advanceUntilIdle()

        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertTrue(progress.indeterminate)
        assertSame(
            fixture.localizedStrings.getValue("Updating and starting workspace…"),
            progress.description,
        )

        fixture.environment.update(
            fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.PENDING)),
            null,
        )
        val polledProgress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(
            fixture.localizedStrings.getValue("Building ${fixture.workspaceName} (pending)…"),
            polledProgress.description
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `update and start discovers its build through the workspace stream`() = runTest {
        val fixture = fixture(
            this,
            workspaceStatus = WorkspaceStatus.STOPPED,
            outdated = true,
            workspaceProgressWebSockets = true,
        )
        val workspaceMessage = slot<(WorkspaceWatchEvent) -> Unit>()
        val workspaceOpen = slot<() -> Unit>()
        val buildLogMessage = slot<(ProvisionerJobLog) -> Unit>()
        every {
            fixture.client.streamWorkspace(any(), capture(workspaceOpen), capture(workspaceMessage), any(), any())
        } returns mockk<WebSocket>(relaxed = true)
        every {
            fixture.client.streamWorkspaceBuildLogs(any(), any(), capture(buildLogMessage), any(), any())
        } returns mockk<WebSocket>(relaxed = true)
        val newBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID(), status = WorkspaceStatus.PENDING)
        coEvery { fixture.client.updateWorkspace(any()) } returns newBuild

        fixture.action("Update and start").run()
        advanceUntilIdle()
        workspaceOpen.captured()

        val stateBeforeEvent = fixture.environment.state.value
        verify(exactly = 0) { fixture.client.streamWorkspaceBuildLogs(any(), any(), any(), any(), any()) }
        workspaceMessage.captured(WorkspaceWatchEvent("data", fixture.workspace.copy(latestBuild = newBuild)))
        buildLogMessage.captured(buildLog(1, "Provisioning updated workspace"))

        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(fixture.localizedStrings.getValue("Provisioning updated workspace"), progress.description)
        assertSame(stateBeforeEvent, fixture.environment.state.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `stop keeps showing progress after the request finishes`() = runTest {
        val fixture = fixture(this)
        val finishRequest = CompletableDeferred<Unit>()
        coEvery { fixture.client.stopWorkspace(any()) } coAnswers {
            finishRequest.await()
            fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING)
        }

        fixture.action("Stop").run()
        runCurrent()

        val queuedState = assertIs<CustomRemoteEnvironmentStateV2>(fixture.environment.state.value)
        assertSame(fixture.localizedStrings.getValue("Queued"), queuedState.label)

        finishRequest.complete(Unit)
        advanceUntilIdle()

        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertTrue(progress.indeterminate)
        assertSame(
            fixture.localizedStrings.getValue("Stopping workspace…"),
            progress.description,
        )

        fixture.environment.update(
            fixture.workspace.copy(latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING)),
            fixture.agent,
        )
        val polledProgress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(
            fixture.localizedStrings.getValue("Building ${fixture.workspaceName} (stopping)…"),
            polledProgress.description
        )
    }

    @Test
    fun `polled build statuses update progress until the build finishes`() = runTest {
        val fixture = fixture(backgroundScope, workspaceStatus = WorkspaceStatus.PENDING)

        val queued = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(
            fixture.localizedStrings.getValue("Building ${fixture.workspaceName} (pending)…"),
            queued.description,
        )

        val startingWorkspace = fixture.workspace.copy(
            latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STARTING),
        )
        fixture.environment.update(startingWorkspace, null)
        val starting = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(
            fixture.localizedStrings.getValue("Building ${fixture.workspaceName} (starting)…"),
            starting.description,
        )

        val stoppingWorkspace = fixture.workspace.copy(
            latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING),
        )
        fixture.environment.update(stoppingWorkspace, null)
        val stopping = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(
            fixture.localizedStrings.getValue("Building ${fixture.workspaceName} (stopping)…"),
            stopping.description,
        )

        val stoppedWorkspace = fixture.workspace.copy(
            latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPED),
        )
        fixture.environment.update(stoppedWorkspace, null)
        val finished = assertIs<EnvironmentDescription.General>(fixture.environment.description.value)
        assertSame(fixture.localizedStrings.getValue(fixture.workspace.templateDisplayName), finished.description)
    }

    @Test
    fun `workspace poll does not request logs for a completed build`() = runTest {
        val fixture = fixture(backgroundScope)

        fixture.environment.update(fixture.workspace, fixture.agent)

        coVerify(exactly = 0) { fixture.client.workspaceBuildLogs(any()) }
    }

    @Test
    fun `finished watchers are replaced only when another active build is polled`() = runTest {
        for (completeThroughStream in listOf(false, true)) {
            val fixture = fixture(
                backgroundScope,
                workspaceStatus = WorkspaceStatus.STARTING,
                workspaceProgressWebSockets = true,
            )
            val workspaceSocket = mockk<WebSocket>(relaxed = true)
            val workspaceMessage = slot<(WorkspaceWatchEvent) -> Unit>()
            val workspaceOpen = slot<() -> Unit>()
            every {
                fixture.client.streamWorkspace(any(), capture(workspaceOpen), capture(workspaceMessage), any(), any())
            } returns workspaceSocket
            every {
                fixture.client.streamWorkspaceBuildLogs(any(), any(), any(), any(), any())
            } returns mockk<WebSocket>(relaxed = true)

            fixture.environment.update(fixture.workspace, fixture.agent)
            workspaceOpen.captured()
            val completedWorkspace = fixture.workspace.copy(
                latestBuild = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.RUNNING),
            )
            if (completeThroughStream) {
                workspaceMessage.captured(WorkspaceWatchEvent("data", completedWorkspace))
            }
            fixture.environment.update(completedWorkspace, fixture.agent)
            fixture.environment.update(completedWorkspace, fixture.agent)

            assertIs<EnvironmentDescription.General>(fixture.environment.description.value)
            verify(exactly = 1) { workspaceSocket.close(1000, any()) }
            verify(exactly = 1) { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) }

            val nextWorkspace = completedWorkspace.copy(
                latestBuild = completedWorkspace.latestBuild.copy(
                    id = UUID.randomUUID(),
                    status = WorkspaceStatus.STOPPING
                ),
            )
            coEvery { fixture.client.workspaceBuildLogs(nextWorkspace.latestBuild.id) } returns
                    listOf(buildLog(1, "Stopping the next build"))
            fixture.environment.update(nextWorkspace, fixture.agent)

            val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
            assertSame(fixture.localizedStrings.getValue("Stopping the next build"), progress.description)
            verify(exactly = 2) { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) }
            fixture.environment.dispose()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `workspace poll shows the latest log from an active build`() = runTest {
        val fixture = fixture(this, workspaceStatus = WorkspaceStatus.PENDING)
        val build = fixture.workspace.latestBuild
        coEvery { fixture.client.workspaceBuildLogs(build.id) } returnsMany listOf(
            listOf(
                buildLog(1, "Preparing workspace"),
                buildLog(2, "\u001B[92mApplying workspace resources\u001B[0m"),
            ),
            listOf(
                buildLog(1, "Preparing workspace"),
                buildLog(2, "Applying workspace resources"),
                buildLog(3, "Starting workspace applications"),
            ),
        )

        val workspaceSnapshot = fixture.workspace.copy(latestBuild = build)

        fixture.environment.update(workspaceSnapshot, null)
        val firstProgress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(
            fixture.localizedStrings.getValue("Applying workspace resources"),
            firstProgress.description,
        )

        fixture.environment.update(workspaceSnapshot, null)
        val nextProgress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(
            fixture.localizedStrings.getValue("Starting workspace applications"),
            nextProgress.description,
        )
        verify(exactly = 0) {
            fixture.client.streamWorkspace(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }

        fixture.environment.update(
            workspaceSnapshot.copy(latestBuild = build.copy(status = WorkspaceStatus.RUNNING)),
            fixture.agent,
        )
        assertIs<EnvironmentDescription.General>(fixture.environment.description.value)
        coVerify(exactly = 2) { fixture.client.workspaceBuildLogs(build.id) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `websocket progress changes the description without changing polled state`() = runTest {
        val fixture = fixture(
            this,
            workspaceStatus = WorkspaceStatus.STOPPED,
            outdated = true,
            workspaceProgressWebSockets = true,
        )
        val workspaceMessage = slot<(WorkspaceWatchEvent) -> Unit>()
        val workspaceOpen = slot<() -> Unit>()
        val buildLogMessage = slot<(ProvisionerJobLog) -> Unit>()
        every {
            fixture.client.streamWorkspace(
                any(),
                capture(workspaceOpen),
                capture(workspaceMessage),
                any(),
                any(),
            )
        } returns mockk<WebSocket>(relaxed = true)
        every {
            fixture.client.streamWorkspaceBuildLogs(
                any(),
                any(),
                capture(buildLogMessage),
                any(),
                any(),
            )
        } returns mockk<WebSocket>(relaxed = true)
        val newBuild = fixture.workspace.latestBuild.copy(id = UUID.randomUUID(), status = WorkspaceStatus.PENDING)
        coEvery { fixture.client.updateWorkspace(any()) } returns newBuild
        fixture.action("Update and start").run()
        advanceUntilIdle()
        workspaceOpen.captured()
        val polledState = fixture.environment.state.value

        fixture.environment.update(fixture.workspace, null)
        workspaceMessage.captured(
            WorkspaceWatchEvent(
                "data",
                fixture.workspace.copy(latestBuild = newBuild.copy(status = WorkspaceStatus.STARTING)),
            ),
        )
        buildLogMessage.captured(buildLog(1, "\u001B[92mApplying workspace resources\u001B[0m"))

        assertSame(polledState, fixture.environment.state.value)
        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(fixture.localizedStrings.getValue("Applying workspace resources"), progress.description)
        coVerify(exactly = 0) { fixture.client.workspaceBuildLogs(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `workspace polling resumes progress requests after websocket failure`() = runTest {
        val fixture = fixture(
            this,
            workspaceStatus = WorkspaceStatus.STOPPED,
            outdated = true,
            workspaceProgressWebSockets = true,
        )
        val onFailure = slot<(Throwable) -> Unit>()
        every {
            fixture.client.streamWorkspace(
                any(),
                any(),
                any(),
                any(),
                capture(onFailure),
            )
        } returns mockk<WebSocket>(relaxed = true)
        every {
            fixture.client.streamWorkspaceBuildLogs(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns mockk<WebSocket>(relaxed = true)
        val build = fixture.workspace.latestBuild.copy(id = UUID.randomUUID(), status = WorkspaceStatus.PENDING)
        coEvery { fixture.client.updateWorkspace(any()) } returns build
        coEvery { fixture.client.workspaceBuildLogs(build.id) } returns listOf(buildLog(1, "Polling fallback"))

        fixture.action("Update and start").run()
        advanceUntilIdle()
        onFailure.captured(IllegalStateException("WebSockets blocked"))
        val activeWorkspace = fixture.workspace.copy(latestBuild = build)
        fixture.environment.update(activeWorkspace, null)
        fixture.environment.update(activeWorkspace, null)

        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertSame(fixture.localizedStrings.getValue("Polling fallback"), progress.description)
        coVerify(exactly = 2) { fixture.client.workspaceBuildLogs(build.id) }
        verify(exactly = 1) { fixture.client.streamWorkspace(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `build progress failure includes the current session id`() = runTest {
        val fixture = fixture(backgroundScope)

        try {
            fixture.environment.beforeConnection()
            val sessionId = assertNotNull(fixture.currentSessionId())
            val build = fixture.workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING)
            val failure = IllegalStateException("logs unavailable")
            coEvery { fixture.client.workspaceBuildLogs(build.id) } throws failure

            fixture.environment.update(fixture.workspace.copy(latestBuild = build), fixture.agent)

            verify(exactly = 1) {
                fixture.logger.warn(
                    failure,
                    "client_session_id=$sessionId Failed to retrieve progress for workspace build ${build.id}",
                )
            }
        } finally {
            fixture.removeSession()
        }
    }

    @Test
    fun `disposing an environment without a session is a no-op`() = runTest {
        val fixture = fixture(backgroundScope)
        clearMocks(fixture.logger, answers = false, recordedCalls = true)

        fixture.environment.dispose()
        fixture.environment.dispose()

        assertNull(fixture.currentSessionId())
        verify { fixture.logger wasNot Called }
    }

    private fun fixture(
        scope: CoroutineScope,
        autoConnect: Boolean = false,
        workspaceStatus: WorkspaceStatus = WorkspaceStatus.RUNNING,
        outdated: Boolean = false,
        workspaceProgressWebSockets: Boolean = false,
    ): Fixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val workspaceName = "workspace-$suffix"
        val agentName = "agent-$suffix"
        val workspace =
            DataGen.workspace(
                name = workspaceName,
                agents = mapOf(agentName to UUID.randomUUID().toString()),
            ).let {
                it.copy(
                    latestBuild = it.latestBuild.copy(status = workspaceStatus),
                    outdated = outdated,
                )
            }
        val agent = requireNotNull(workspace.latestBuild.resources.single().agents).single()
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val settingsStore = mockk<CoderSettingsStore>(relaxed = true)
        val i18n = mockk<LocalizableStringFactory>(relaxed = true)
        val localizedStrings = mutableMapOf<String, LocalizableString>()
        every { i18n.ptrl(any<String>()) } answers {
            localizedStrings.getOrPut(firstArg()) { mockk(relaxed = true) }
        }
        every { i18n.pnotr(any<String>()) } answers {
            localizedStrings.getOrPut(firstArg()) { mockk(relaxed = true) }
        }
        val coderLogger = CoderLogger(logger, mockk<ToolboxUi>(relaxed = true), scope, i18n)
        every { context.cs } returns scope
        every { context.logger } returns coderLogger
        every { context.settingsStore } returns settingsStore
        every { context.i18n } returns i18n
        every { context.envStateColorPalette } returns mockk<EnvironmentStateColorPalette>(relaxed = true)
        every { settingsStore.shouldAutoConnect(any()) } returns autoConnect

        val client = mockk<CoderRestClient>(relaxed = true)
        val cli = mockk<CoderCLIManager>(relaxed = true)
        every { cli.features } returns Features(workspaceProgressWebSockets = workspaceProgressWebSockets)
        val environment = CoderRemoteEnvironment(
            context = context,
            client = client,
            cli = cli,
            workspaceRefreshTrigger = Channel(Channel.CONFLATED),
            workspace = workspace,
            agent = agent.takeIf { workspaceStatus == WorkspaceStatus.RUNNING },
        )
        return Fixture(
            environment,
            logger,
            settingsStore,
            workspace,
            agent,
            workspaceName,
            agentName,
            client,
            cli,
            localizedStrings,
        )
    }

    private fun isSessionStartedMessage(message: String): Boolean =
        message.contains("session", ignoreCase = true) &&
                (message.contains("start", ignoreCase = true) || message.contains("creat", ignoreCase = true))

    private fun isSessionDisposedMessage(message: String): Boolean =
        message.contains("session", ignoreCase = true) &&
                (message.contains("dispos", ignoreCase = true) ||
                        message.contains("remov", ignoreCase = true) ||
                        message.contains("end", ignoreCase = true))

    private fun hasSessionId(message: String, sessionId: SessionId): Boolean =
        message.startsWith("client_session_id=$sessionId ")

    private fun buildLog(id: Long, output: String) = ProvisionerJobLog(
        id = id,
        createdAt = Instant.EPOCH,
        source = "provisioner",
        level = "info",
        stage = "Building",
        output = output,
    )

    private data class Fixture(
        val environment: CoderRemoteEnvironment,
        val logger: Logger,
        val settingsStore: CoderSettingsStore,
        val workspace: Workspace,
        val agent: WorkspaceAgent,
        val workspaceName: String,
        val agentName: String,
        val client: CoderRestClient,
        val cli: CoderCLIManager,
        val localizedStrings: Map<String, LocalizableString>,
    ) {
        fun action(label: String): Action {
            val localizedLabel = localizedStrings.getValue(label)
            return environment.actionsList.value
                .filterIsInstance<Action>()
                .single { it.label === localizedLabel }
        }

        fun currentSessionId(): SessionId? = SessionIdRegistry.findSession(workspaceName, agentName)

        fun removeSession() {
            SessionIdRegistry.removeSession(workspaceName, agentName)
        }
    }
}
