package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.CoderCLIManager
import com.coder.toolbox.cli.WorkspaceAddress
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.store.CoderSettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentViewTest {
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
}
