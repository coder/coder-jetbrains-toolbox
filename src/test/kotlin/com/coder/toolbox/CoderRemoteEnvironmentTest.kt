package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.Features
import com.coder.toolbox.diagnostics.CoderLogger
import com.coder.toolbox.sdk.CoderRestClient
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.session.SessionId
import com.coder.toolbox.session.SessionIdRegistry
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.views.Action
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.environments.SshEnvironmentContentsView
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoderRemoteEnvironmentTest {
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `start action reports CLI failures and refreshes the workspace`() = runTest {
        val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = fixture(actionScope, workspaceStatus = WorkspaceStatus.STOPPED)
        val failure = IllegalStateException("start failed")
        val errorLogged = CompletableDeferred<Pair<Throwable, String>>()
        every { fixture.cli.features } returns Features()
        every { fixture.cli.startWorkspace(any(), any()) } throws failure
        every { fixture.logger.error(any<Throwable>(), any<String>()) } answers {
            errorLogged.complete(firstArg<Throwable>() to secondArg<String>())
        }

        try {
            val startAction = fixture.environment.actionsList.value[2] as Action

            startAction.run()
            val loggedError = withContext(Dispatchers.IO) {
                withTimeout(5_000) { errorLogged.await() }
            }

            verify(exactly = 1) {
                fixture.cli.startWorkspace(any(), any())
            }
            assertEquals(failure::class, loggedError.first::class)
            assertEquals(failure.message, loggedError.first.message)
            assertEquals("start failed", loggedError.second)
            assertEquals(true, fixture.workspaceRefreshTrigger.tryReceive().getOrNull())
        } finally {
            fixture.environment.dispose()
            fixture.removeSession()
            actionScope.cancel()
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
    ): Fixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val workspaceName = "workspace-$suffix"
        val agentName = "agent-$suffix"
        val generatedWorkspace = DataGen.workspace(
            name = workspaceName,
            agents = mapOf(agentName to UUID.randomUUID().toString()),
        )
        val workspace = generatedWorkspace.copy(
            latestBuild = generatedWorkspace.latestBuild.copy(status = workspaceStatus),
        )
        val agent = requireNotNull(workspace.latestBuild.resources.single().agents).single()
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val settingsStore = mockk<CoderSettingsStore>(relaxed = true)
        val i18n = mockk<LocalizableStringFactory>(relaxed = true)
        val coderLogger = CoderLogger(logger, mockk<ToolboxUi>(relaxed = true), scope, i18n)
        val cli = mockk<CoderCLIManager>(relaxed = true)
        val workspaceRefreshTrigger = Channel<Boolean>(Channel.CONFLATED)

        every { context.cs } returns scope
        every { context.logger } returns coderLogger
        every { context.settingsStore } returns settingsStore
        every { context.i18n } returns i18n
        every { context.envStateColorPalette } returns mockk<EnvironmentStateColorPalette>(relaxed = true)
        every { settingsStore.shouldAutoConnect(any()) } returns autoConnect

        val environment = CoderRemoteEnvironment(
            context = context,
            client = mockk<CoderRestClient>(relaxed = true),
            cli = cli,
            workspaceRefreshTrigger = workspaceRefreshTrigger,
            workspace = workspace,
            agent = agent,
        )
        return Fixture(
            environment,
            logger,
            settingsStore,
            cli,
            workspaceRefreshTrigger,
            workspace,
            agent,
            workspaceName,
            agentName,
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

    private data class Fixture(
        val environment: CoderRemoteEnvironment,
        val logger: Logger,
        val settingsStore: CoderSettingsStore,
        val cli: CoderCLIManager,
        val workspaceRefreshTrigger: Channel<Boolean>,
        val workspace: Workspace,
        val agent: WorkspaceAgent,
        val workspaceName: String,
        val agentName: String,
    ) {
        fun currentSessionId(): SessionId? = SessionIdRegistry.findSession(workspaceName, agentName)

        fun removeSession() {
            SessionIdRegistry.removeSession(workspaceName, agentName)
        }
    }
}
