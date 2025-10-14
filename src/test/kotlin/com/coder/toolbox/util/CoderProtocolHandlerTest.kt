package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.oauth.CoderAccount
import com.coder.toolbox.oauth.CoderOAuthCfg
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.views.CoderSettingsPage
import com.jetbrains.toolbox.api.core.auth.PluginAuthManager
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.os.LocalDesktopManager
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import com.jetbrains.toolbox.api.remoteDev.connection.ToolboxProxySettings
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class CoderProtocolHandlerTest {

    private companion object {
        val AGENT_RIKER = AgentTestData(name = "Riker", id = "9a920eee-47fb-4571-9501-e4b3120c12f2")
        val AGENT_BILL = AgentTestData(name = "Bill", id = "fb3daea4-da6b-424d-84c7-36b90574cfef")
        val AGENT_BOB = AgentTestData(name = "Bob", id = "b0e4c54d-9ba9-4413-8512-11ca1e826a24")

        val ALL_AGENTS = mapOf(
            AGENT_BOB.name to AGENT_BOB.id,
            AGENT_BILL.name to AGENT_BILL.id,
            AGENT_RIKER.name to AGENT_RIKER.id
        )

        val SINGLE_AGENT = mapOf(AGENT_BOB.name to AGENT_BOB.id)
    }

    private val context = CoderToolboxContext(
        mockk<PluginAuthManager<CoderAccount, CoderOAuthCfg>>(),
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
        CoderSettingsPage(context, Channel(Channel.CONFLATED)),
        MutableStateFlow(ProviderVisibilityState(applicationVisible = true, providerVisible = true)),
        MutableStateFlow(false)
    )

    @Test
    fun `given a workspace with multiple agents when getMatchingAgent is called with a valid agent name then it correctly resolves resolves an agent`() {
        val ws = DataGen.workspace("ws", agents = ALL_AGENTS)

        val testCases = listOf(
            AgentMatchTestCase(
                "resolves agent with name Riker",
                mapOf("agent_name" to AGENT_RIKER.name),
                AGENT_RIKER.uuid
            ),
            AgentMatchTestCase(
                "resolves agent with name Bill",
                mapOf("agent_name" to AGENT_BILL.name),
                AGENT_BILL.uuid
            ),
            AgentMatchTestCase(
                "resolves agent with name Bob",
                mapOf("agent_name" to AGENT_BOB.name),
                AGENT_BOB.uuid
            )
        )

        runBlocking {
            testCases.forEach { testCase ->
                assertEquals(
                    testCase.expectedAgentId,
                    protocolHandler.getMatchingAgent(testCase.params, ws)?.id,
                    "Failed: ${testCase.description}"
                )
            }
        }
    }

    @Test
    fun `given a workspace with multiple agents when getMatchingAgent is called with invalid agent names then no agent is resolved`() {
        val ws = DataGen.workspace("ws", agents = ALL_AGENTS)

        val testCases = listOf(
            AgentNullResultTestCase(
                "empty parameters (i.e. no agent name) does not return any agent",
                emptyMap()
            ),
            AgentNullResultTestCase(
                "empty agent_name does not return any agent",
                mapOf("agent_name" to "")
            ),
            AgentNullResultTestCase(
                "null agent_name does not return any agent",
                mapOf("agent_name" to null)
            ),
            AgentNullResultTestCase(
                "non-existent agent does not return any agent",
                mapOf("agent_name" to "agent_name_homer")
            ),
            AgentNullResultTestCase(
                "UUID instead of name does not return any agent",
                mapOf("agent_name" to "not-an-agent-name")
            )
        )

        runBlocking {
            testCases.forEach { testCase ->
                assertNull(
                    protocolHandler.getMatchingAgent(testCase.params, ws)?.id,
                    "Failed: ${testCase.description}"
                )
            }
        }
    }

    @Test
    fun `given a workspace with a single agent when getMatchingAgent is called with an empty agent name then the default agent is resolved`() {
        val ws = DataGen.workspace("ws", agents = SINGLE_AGENT)

        val testCases = listOf(
            AgentMatchTestCase(
                "empty parameters (i.e. no agent name) auto-selects the one and only agent available",
                emptyMap(),
                AGENT_BOB.uuid
            ),
            AgentMatchTestCase(
                "empty agent_name auto-selects the one and only agent available",
                mapOf("agent_name" to ""),
                AGENT_BOB.uuid
            ),
            AgentMatchTestCase(
                "null agent_name auto-selects the one and only agent available",
                mapOf("agent_name" to null),
                AGENT_BOB.uuid
            )
        )

        runBlocking {
            testCases.forEach { testCase ->
                assertEquals(
                    testCase.expectedAgentId,
                    protocolHandler.getMatchingAgent(testCase.params, ws)?.id,
                    "Failed: ${testCase.description}"
                )
            }
        }
    }

    @Test
    fun `given a workspace with a single agent when getMatchingAgent is called with an invalid agent name then no agent is resolved`() {
        val ws = DataGen.workspace("ws", agents = SINGLE_AGENT)

        val testCase = AgentNullResultTestCase(
            "non-matching agent_name with single agent",
            mapOf("agent_name" to "agent_name_garfield")
        )

        runBlocking {
            assertNull(
                protocolHandler.getMatchingAgent(testCase.params, ws),
                "Failed: ${testCase.description}"
            )
        }
    }

    @Test
    fun `given a workspace with no agent when getMatchingAgent is called then no agent is resolved`() {
        val ws = DataGen.workspace("ws")

        val testCases = listOf(
            AgentNullResultTestCase(
                "empty parameters (i.e. no agent name) does not return any agent",
                emptyMap()
            ),
            AgentNullResultTestCase(
                "empty agent_name does not return any agent",
                mapOf("agent_name" to "")
            ),
            AgentNullResultTestCase(
                "null agent_name does not return any agent",
                mapOf("agent_name" to null)
            ),
            AgentNullResultTestCase(
                "valid agent_name does not return any agent",
                mapOf("agent_name" to AGENT_RIKER.name)
            )
        )

        runBlocking {
            testCases.forEach { testCase ->
                assertNull(
                    protocolHandler.getMatchingAgent(testCase.params, ws),
                    "Failed: ${testCase.description}"
                )
            }
        }
    }

    internal data class AgentTestData(val name: String, val id: String) {
        val uuid: UUID get() = UUID.fromString(id)
    }

    internal data class AgentMatchTestCase(
        val description: String,
        val params: Map<String, String?>,
        val expectedAgentId: UUID
    )

    internal data class AgentNullResultTestCase(
        val description: String,
        val params: Map<String, String?>
    )
}