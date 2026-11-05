package com.coder.toolbox.models

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.sdk.convertors.ArchConverter
import com.coder.toolbox.sdk.convertors.OSConverter
import com.coder.toolbox.sdk.convertors.UUIDConverter
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentScript
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.jetbrains.toolbox.api.remoteDev.states.StandardRemoteEnvironmentState
import com.squareup.moshi.Moshi
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class WorkspaceAndAgentStatusTest {
    private val workspace = DataGen.workspace("startup", agents = mapOf("dev" to UUID.randomUUID().toString()))
    private val agent = workspace.latestBuild.resources.single().agents?.single()
        ?: fail("The test workspace has no agents")

    @Test
    fun `SSH eligibility follows lifecycle and startup script policy`() {
        for (blocking in listOf(false, true)) {
            for (lifecycle in WorkspaceAgentLifecycleState.entries) {
                val status = WorkspaceAndAgentStatus.from(workspace, agent.copy(
                    lifecycleState = lifecycle,
                    scripts = listOf(WorkspaceAgentScript(runOnStart = true, startBlocksLogin = blocking)),
                ))
                val expected = lifecycle in setOf(
                    WorkspaceAgentLifecycleState.READY,
                    WorkspaceAgentLifecycleState.START_ERROR,
                    WorkspaceAgentLifecycleState.START_TIMEOUT,
                ) || (lifecycle == WorkspaceAgentLifecycleState.STARTING && !blocking)
                assertEquals(expected, status.canConnect(), "lifecycle=$lifecycle blocking=$blocking")
                if (expected) {
                    assertTrue(status.canStop())
                    assertFalse(status.pending())
                }
            }
        }
    }

    @Test
    fun `running workspace and connected agent are required even after startup finishes`() {
        for (workspaceStatus in WorkspaceStatus.entries) {
            for (agentStatus in WorkspaceAgentStatus.entries) {
                val status = WorkspaceAndAgentStatus.from(
                    workspace.copy(latestBuild = workspace.latestBuild.copy(status = workspaceStatus)),
                    agent.copy(status = agentStatus),
                )
                assertEquals(
                    workspaceStatus == WorkspaceStatus.RUNNING && agentStatus == WorkspaceAgentStatus.CONNECTED,
                    status.canConnect(), "$workspaceStatus / $agentStatus")
            }
        }
        assertFalse(WorkspaceAndAgentStatus.from(workspace).canConnect())
    }

    @Test
    fun `only blocking startup scripts delay connection and any one can block`() {
        val nonStartup = WorkspaceAgentScript(runOnStart = false, startBlocksLogin = true)
        val nonBlocking = WorkspaceAgentScript(runOnStart = true, startBlocksLogin = false)
        val blocking = WorkspaceAgentScript(runOnStart = true, startBlocksLogin = true)
        for (scripts in listOf(emptyList(), listOf(nonStartup), listOf(nonBlocking, nonStartup))) {
            assertTrue(WorkspaceAndAgentStatus.from(workspace, agent.copy(
                lifecycleState = WorkspaceAgentLifecycleState.STARTING, scripts = scripts,
            )).canConnect())
        }
        assertFalse(WorkspaceAndAgentStatus.from(workspace, agent.copy(
            lifecycleState = WorkspaceAgentLifecycleState.STARTING, scripts = listOf(nonBlocking, blocking),
        )).canConnect())
    }

    @Test
    fun `startup failure remains unhealthy and keeps its warning color when connectable`() {
        for (lifecycle in listOf(
            WorkspaceAgentLifecycleState.START_ERROR, WorkspaceAgentLifecycleState.START_TIMEOUT,
        )) {
            val context = mockk<CoderToolboxContext>(relaxed = true)
            val status = WorkspaceAndAgentStatus.from(workspace, agent.copy(lifecycleState = lifecycle))
            assertTrue(status.canConnect())
            assertTrue(status.unhealthy())
            val palette = context.envStateColorPalette
            status.toRemoteEnvironmentState(context)
            verify(exactly = 1) { palette.getColor(StandardRemoteEnvironmentState.Unhealthy) }
            verify(exactly = 0) { palette.getColor(StandardRemoteEnvironmentState.Active) }
        }
    }

    @Test
    fun `agent JSON uses script flags without requiring later script metadata or login_before_ready`() {
        val adapter = Moshi.Builder().add(UUIDConverter()).add(ArchConverter()).add(OSConverter())
            .build().adapter(WorkspaceAgent::class.java)
        val decoded = adapter.fromJson("""{
            "id":"${agent.id}", "name":"dev", "status":"connected", "lifecycle_state":"starting",
            "scripts":[{"run_on_start":true,"start_blocks_login":false,"script":"sleep 180"}]
        }""") ?: fail("The agent JSON decoded to null")
        assertTrue(WorkspaceAndAgentStatus.from(workspace, decoded).canConnect())
        assertEquals(listOf(WorkspaceAgentScript(true, false)), decoded.scripts)
    }
}
