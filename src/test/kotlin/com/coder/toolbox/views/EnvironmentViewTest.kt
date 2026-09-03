package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.WorkspaceAddress
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.session.SessionIdRegistry
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.OS
import com.jetbrains.toolbox.api.remoteDev.deploy.DeploymentTarget
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EnvironmentViewTest {
    @Test
    fun `deployment target follows the workspace agent operating system`() {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val cli = mockk<CoderCLIManager>()
        val workspace = mockk<Workspace>()
        val url = URL("https://coder.example.com")
        val cases = listOf(
            OS.LINUX to DeploymentTarget.LINUX,
            OS.MAC to DeploymentTarget.MACOS,
            OS.WINDOWS to DeploymentTarget.WINDOWS,
            null to DeploymentTarget.AUTO,
        )

        cases.forEach { (operatingSystem, expectedTarget) ->
            val agent = mockk<WorkspaceAgent> {
                every { this@mockk.operatingSystem } returns operatingSystem
            }

            val deploymentSettings = EnvironmentView(context, url, cli, workspace, agent).deploymentSettings

            assertEquals(expectedTarget, deploymentSettings.deploymentTarget)
        }
    }

    @Test
    fun `connection info passes the configured SSH config path to Toolbox`() = runBlocking {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val settings = mockk<CoderSettingsStore>()
        val cli = mockk<CoderCLIManager>()
        val workspace = mockk<Workspace> {
            every { name } returns "workspace"
            every { ownerName } returns "owner"
        }
        val agent = mockk<WorkspaceAgent> {
            every { name } returns "agent"
        }
        val url = URL("https://coder.example.com")
        val configuredPath = "/tmp/coder-toolbox-test/config"

        every { context.settingsStore } returns settings
        every { settings.sshConfigPath } returns configuredPath
        every { cli.getHostname(url, any<WorkspaceAddress>()) } returns "coder.example.com--workspace.agent"

        val connectionInfo = EnvironmentView(context, url, cli, workspace, agent).getConnectionInfo()

        assertEquals(configuredPath, connectionInfo.sshConfigPath)
    }

    @Test
    fun `connection info resolves the current Toolbox session for the SSH process`() = runBlocking {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val cli = mockk<CoderCLIManager>()
        val workspace = mockk<Workspace> {
            every { name } returns "workspace"
            every { ownerName } returns "owner"
        }
        val agent = mockk<WorkspaceAgent> {
            every { name } returns "agent"
        }
        val url = URL("https://coder.example.com")
        every { cli.getHostname(url, any<WorkspaceAddress>()) } returns "coder.example.com--workspace.agent"

        val view = EnvironmentView(context, url, cli, workspace, agent)
        val connectionInfoWithoutSession = view.getConnectionInfo()
        assertEquals(null, connectionInfoWithoutSession.environment)

        val firstSessionId = SessionIdRegistry.startSession(context, workspace.name, agent.name)
        try {
            val firstConnectionInfo = view.getConnectionInfo()
            assertEquals(
                mapOf("CODER_TRACE_SESSION_ID" to firstSessionId.value),
                firstConnectionInfo.environment,
            )

            SessionIdRegistry.removeSession(workspace.name, agent.name)
            val secondSessionId = SessionIdRegistry.startSession(context, workspace.name, agent.name)
            val secondConnectionInfo = view.getConnectionInfo()

            assertNotEquals(firstSessionId, secondSessionId)
            assertEquals(
                mapOf("CODER_TRACE_SESSION_ID" to firstSessionId.value),
                firstConnectionInfo.environment,
            )
            assertEquals(
                mapOf("CODER_TRACE_SESSION_ID" to secondSessionId.value),
                secondConnectionInfo.environment,
            )
        } finally {
            SessionIdRegistry.removeSession(workspace.name, agent.name)
        }
    }
}
