package com.coder.toolbox

import com.coder.toolbox.cli.CoderCLIManager
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
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.environments.SshEnvironmentContentsView
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentDescription
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun `start action shows progress until the CLI command finishes`() = runTest {
        val fixture = fixture(this, workspaceStatus = WorkspaceStatus.STOPPED)
        val commandStarted = CountDownLatch(1)
        val finishCommand = CountDownLatch(1)
        every { fixture.cli.startWorkspace(any()) } answers {
            commandStarted.countDown()
            check(finishCommand.await(5, TimeUnit.SECONDS))
            ""
        }

        fixture.action("Start").run()
        runCurrent()

        assertTrue(commandStarted.await(5, TimeUnit.SECONDS))
        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertTrue(progress.indeterminate)
        assertSame(fixture.localizedStrings.getValue("Starting workspace…"), progress.description)

        finishCommand.countDown()
        advanceUntilIdle()
        verify(exactly = 1) { fixture.cli.startWorkspace(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `REST workspace action shows progress until the request finishes`() = runTest {
        val fixture = fixture(this, outdated = true)
        val initialDescription = fixture.environment.description.value
        val requestStarted = CompletableDeferred<Unit>()
        val finishRequest = CompletableDeferred<Unit>()
        coEvery { fixture.client.updateWorkspace(any()) } coAnswers {
            requestStarted.complete(Unit)
            finishRequest.await()
            fixture.workspace.latestBuild
        }

        fixture.action("Update and restart").run()
        runCurrent()

        assertTrue(requestStarted.isCompleted)
        val progress = assertIs<EnvironmentDescription.Progress>(fixture.environment.description.value)
        assertTrue(progress.indeterminate)
        assertSame(fixture.localizedStrings.getValue("Updating and restarting workspace…"), progress.description)

        finishRequest.complete(Unit)
        advanceUntilIdle()
        assertSame(initialDescription, fixture.environment.description.value)
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
        val coderLogger = CoderLogger(logger, mockk<ToolboxUi>(relaxed = true), scope, i18n)
        every { context.cs } returns scope
        every { context.logger } returns coderLogger
        every { context.settingsStore } returns settingsStore
        every { context.i18n } returns i18n
        every { context.envStateColorPalette } returns mockk<EnvironmentStateColorPalette>(relaxed = true)
        every { settingsStore.shouldAutoConnect(any()) } returns autoConnect

        val client = mockk<CoderRestClient>(relaxed = true)
        val cli = mockk<CoderCLIManager>(relaxed = true)
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
