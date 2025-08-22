package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.os.LocalDesktopManager
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import com.jetbrains.toolbox.api.remoteDev.connection.ToolboxProxySettings
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class CoderProtocolHandlerTest {
    private val context = CoderToolboxContext(
        mockk<ToolboxUi>(relaxed = true),
        mockk<EnvironmentUiPageManager>(),
        mockk<EnvironmentStateColorPalette>(),
        mockk<RemoteToolsHelper>(),
        mockk<ClientHelper>(),
        mockk<LocalDesktopManager>(),
        mockk<CoroutineScope>(),
        mockk<Logger>(relaxed = true),
        mockk<LocalizableStringFactory>(relaxed = true),
        CoderSettingsStore(pluginTestSettingsStore(), Environment(), mockk<Logger>(relaxed = true)),
        mockk<CoderSecretsStore>(),
        mockk<ToolboxProxySettings>()
    )

    private val protocolHandler = CoderProtocolHandler(
        context,
        DialogUi(context),
        MutableStateFlow(false),
        visibilityState,
        isInitialized
    )

    private val agents =
        mapOf(
            "agent_name_bob" to "b0e4c54d-9ba9-4413-8512-11ca1e826a24",
            "agent_name_bill" to "fb3daea4-da6b-424d-84c7-36b90574cfef",
            "agent_name_riker" to "9a920eee-47fb-4571-9501-e4b3120c12f2",
        )
    private val agentBob =
        mapOf(
            "agent_name_bob" to "b0e4c54d-9ba9-4413-8512-11ca1e826a24",
        )

    @Test
    @DisplayName("given a ws with multiple agents, expect the correct agent to be resolved if it matches the agent_name query param")
    fun getMatchingAgent() {
        val ws = DataGen.workspace("ws", agents = agents)

        val tests =
            listOf(
                Pair(
                    mapOf("agent_name" to "agent_name_riker"),
                    "9a920eee-47fb-4571-9501-e4b3120c12f2"
                ),
                Pair(
                    mapOf("agent_name" to "agent_name_bill"),
                    "fb3daea4-da6b-424d-84c7-36b90574cfef"
                ),
                Pair(
                    mapOf("agent_name" to "agent_name_bob"),
                    "b0e4c54d-9ba9-4413-8512-11ca1e826a24"
                )
            )
        runBlocking {
            tests.forEach {
                assertEquals(UUID.fromString(it.second), protocolHandler.getMatchingAgent(it.first, ws)?.id)
            }
        }
    }

    @Test
    @DisplayName("given a ws with only multiple agents expect the agent resolution to fail if none match the agent_name query param")
    fun failsToGetMatchingAgent() {
        val ws = DataGen.workspace("ws", agents = agents)
        val tests =
            listOf(
                Triple(emptyMap(), MissingArgumentException::class, "Unable to determine"),
                Triple(mapOf("agent_name" to ""), MissingArgumentException::class, "Unable to determine"),
                Triple(mapOf("agent_name" to null), MissingArgumentException::class, "Unable to determine"),
                Triple(mapOf("agent_name" to "not-an-agent-name"), IllegalArgumentException::class, "agent with ID"),
                Triple(
                    mapOf("agent_name" to "agent_name_homer"),
                    IllegalArgumentException::class,
                    "agent with name"
                )
            )
        runBlocking {
            tests.forEach {
                assertNull(protocolHandler.getMatchingAgent(it.first, ws)?.id)
            }
        }
    }

    @Test
    @DisplayName("given a ws with only one agent, the agent is selected even when agent_name query param was not provided")
    fun getsFirstAgentWhenOnlyOne() {
        val ws = DataGen.workspace("ws", agents = agentBob)
        val tests =
            listOf(
                emptyMap(),
                mapOf("agent_name" to ""),
                mapOf("agent_name" to null)
            )
        runBlocking {
            tests.forEach {
                assertEquals(
                    UUID.fromString("b0e4c54d-9ba9-4413-8512-11ca1e826a24"),
                    protocolHandler.getMatchingAgent(
                        it,
                        ws,
                    )?.id,
                )
            }
        }
    }

    @Test
    @DisplayName("given a ws with only one agent, the agent is NOT selected when agent_name query param was provided but does not match")
    fun failsToGetAgentWhenOnlyOne() {
        val wsWithAgentBob = DataGen.workspace("ws", agents = agentBob)
        val tests =
            listOf(
                Triple(
                    mapOf("agent_name" to "agent_name_garfield"),
                    IllegalArgumentException::class,
                    "agent with name"
                ),
            )
        runBlocking {
            tests.forEach {
                assertNull(protocolHandler.getMatchingAgent(it.first, wsWithAgentBob))
            }
        }
    }

    @Test
    @DisplayName("fails to resolve any agent when the workspace has no agents")
    fun failsToGetAgentWhenWorkspaceHasNoAgents() {
        val wsWithoutAgents = DataGen.workspace("ws")
        val tests =
            listOf(
                Triple(emptyMap(), IllegalArgumentException::class, "has no agents"),
                Triple(mapOf("agent_name" to ""), IllegalArgumentException::class, "has no agents"),
                Triple(mapOf("agent_name" to null), IllegalArgumentException::class, "has no agents"),
                Triple(
                    mapOf("agent_name" to "agent_name_riker"),
                    IllegalArgumentException::class,
                    "has no agents"
                ),
            )
        runBlocking {
            tests.forEach {
                assertNull(protocolHandler.getMatchingAgent(it.first, wsWithoutAgents))
            }
        }
    }
}
