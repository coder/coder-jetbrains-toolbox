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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class LinkHandlerTest {
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
        MutableStateFlow(false)
    )

    private val agents =
        mapOf(
            "agent_name_3" to "b0e4c54d-9ba9-4413-8512-11ca1e826a24",
            "agent_name_2" to "fb3daea4-da6b-424d-84c7-36b90574cfef",
            "agent_name" to "9a920eee-47fb-4571-9501-e4b3120c12f2",
        )
    private val oneAgent =
        mapOf(
            "agent_name_3" to "b0e4c54d-9ba9-4413-8512-11ca1e826a24",
        )

    @Test
    fun tstgetMatchingAgent() {
        val ws = DataGen.workspace("ws", agents = agents)

        val tests =
            listOf(
                Pair(
                    mapOf("agent_id" to "9a920eee-47fb-4571-9501-e4b3120c12f2"),
                    "9a920eee-47fb-4571-9501-e4b3120c12f2"
                ),
                Pair(
                    mapOf("agent_id" to "fb3daea4-da6b-424d-84c7-36b90574cfef"),
                    "fb3daea4-da6b-424d-84c7-36b90574cfef"
                ),
                Pair(
                    mapOf("agent_id" to "b0e4c54d-9ba9-4413-8512-11ca1e826a24"),
                    "b0e4c54d-9ba9-4413-8512-11ca1e826a24"
                ),
                // Prefer agent_id.
                Pair(
                    mapOf(
                        "agent_id" to "b0e4c54d-9ba9-4413-8512-11ca1e826a24",
                    ),
                    "b0e4c54d-9ba9-4413-8512-11ca1e826a24",
                ),
            )
        runBlocking {
            tests.forEach {
                assertEquals(UUID.fromString(it.second), protocolHandler.getMatchingAgent(it.first, ws)?.id)
            }
        }
    }

    @Test
    fun failsToGetMatchingAgent() {
        val ws = DataGen.workspace("ws", agents = agents)
        val tests =
            listOf(
                Triple(emptyMap(), MissingArgumentException::class, "Unable to determine"),
                Triple(mapOf("agent_id" to ""), MissingArgumentException::class, "Unable to determine"),
                Triple(mapOf("agent_id" to null), MissingArgumentException::class, "Unable to determine"),
                Triple(mapOf("agent_id" to "not-a-uuid"), IllegalArgumentException::class, "agent with ID"),
                Triple(
                    mapOf("agent_id" to "ceaa7bcf-1612-45d7-b484-2e0da9349168"),
                    IllegalArgumentException::class,
                    "agent with ID"
                ),
                // Will ignore agent if agent_id is set even if agent matches.
                Triple(
                    mapOf(
                        "agent" to "agent_name",
                        "agent_id" to "ceaa7bcf-1612-45d7-b484-2e0da9349168",
                    ),
                    IllegalArgumentException::class,
                    "agent with ID",
                ),
            )
        runBlocking {
            tests.forEach {
                assertNull(protocolHandler.getMatchingAgent(it.first, ws)?.id)
            }
        }
    }

    @Test
    fun getsFirstAgentWhenOnlyOne() {
        val ws = DataGen.workspace("ws", agents = oneAgent)
        val tests =
            listOf(
                emptyMap(),
                mapOf("agent" to ""),
                mapOf("agent_id" to ""),
                mapOf("agent" to null),
                mapOf("agent_id" to null),
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
    fun failsToGetAgentWhenOnlyOne() {
        val ws = DataGen.workspace("ws", agents = oneAgent)
        val tests =
            listOf(
                Triple(
                    mapOf("agent_id" to "ceaa7bcf-1612-45d7-b484-2e0da9349168"),
                    IllegalArgumentException::class,
                    "agent with ID"
                ),
            )
        runBlocking {
            tests.forEach {
                assertNull(protocolHandler.getMatchingAgent(it.first, ws)?.id)
            }
        }
    }

    @Test
    fun failsToGetAgentWithoutAgents() {
        val ws = DataGen.workspace("ws")
        val tests =
            listOf(
                Triple(emptyMap(), IllegalArgumentException::class, "has no agents"),
                Triple(mapOf("agent" to ""), IllegalArgumentException::class, "has no agents"),
                Triple(mapOf("agent_id" to ""), IllegalArgumentException::class, "has no agents"),
                Triple(mapOf("agent" to null), IllegalArgumentException::class, "has no agents"),
                Triple(mapOf("agent_id" to null), IllegalArgumentException::class, "has no agents"),
                Triple(mapOf("agent" to "agent_name"), IllegalArgumentException::class, "has no agents"),
                Triple(
                    mapOf("agent_id" to "9a920eee-47fb-4571-9501-e4b3120c12f2"),
                    IllegalArgumentException::class,
                    "has no agents"
                ),
            )
        runBlocking {
            tests.forEach {
                assertNull(protocolHandler.getMatchingAgent(it.first, ws)?.id)
            }
        }
    }
}
