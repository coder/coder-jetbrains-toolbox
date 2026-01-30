package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.feed.Ide
import com.coder.toolbox.feed.IdeFeedManager
import com.coder.toolbox.feed.IdeType
import com.coder.toolbox.feed.JetBrainsFeedService
import com.coder.toolbox.sdk.DataGen
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import java.util.UUID
import kotlin.test.Test

class CoderProtocolHandlerTest {

    private lateinit var context: CoderToolboxContext
    private lateinit var feedService: JetBrainsFeedService
    private lateinit var ideFeedManager: IdeFeedManager
    private lateinit var handler: CoderProtocolHandler
    private lateinit var remoteToolsHelper: RemoteToolsHelper

    // Test Coroutine Scope
    private val dispatcher = StandardTestDispatcher()

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


    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        feedService = mockk(relaxed = true)
        ideFeedManager = IdeFeedManager(context, feedService)
        remoteToolsHelper = mockk(relaxed = true)

        every { context.cs } returns CoroutineScope(dispatcher)
        every { context.remoteIdeOrchestrator } returns remoteToolsHelper

        handler = CoderProtocolHandler(context, ideFeedManager)
    }

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
                kotlin.test.assertEquals(
                    testCase.expectedAgentId,
                    handler.getMatchingAgent(testCase.params, ws)?.id,
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
                kotlin.test.assertNull(
                    handler.getMatchingAgent(testCase.params, ws)?.id,
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
                kotlin.test.assertEquals(
                    testCase.expectedAgentId,
                    handler.getMatchingAgent(testCase.params, ws)?.id,
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
            kotlin.test.assertNull(
                handler.getMatchingAgent(testCase.params, ws),
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
                kotlin.test.assertNull(
                    handler.getMatchingAgent(testCase.params, ws),
                    "Failed: ${testCase.description}"
                )
            }
        }
    }


    @Test
    fun `given empty available tools when resolving latest eap then returns null`() = runTest(dispatcher) {
        coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns emptyList()

        assertNull(handler.resolveIdeIdentifier("env-1", "RR", "latest_eap"))
    }

    @Test
    fun `given no matching eap in feed when resolving latest eap then falls back to max available tool`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-241.1", "RR-240.1")
            // Feed returns empty or irrelevant EAPs
            coEvery { feedService.fetchEapFeed() } returns emptyList()

            assertEquals("RR-241.1", handler.resolveIdeIdentifier("env-1", "RR", "latest_eap"))
        }

    @Test
    fun `given available tools intersects with eap feed when resolving latest eap then returns matched version`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-243.1", "RR-241.1")
            coEvery { feedService.fetchEapFeed() } returns listOf(
                Ide("RR", "252.1", "2025.2", IdeType.EAP),
                Ide("RR", "251.1", "2025.1", IdeType.RELEASE),
                Ide("RR", "243.1", "2024.3", IdeType.EAP)
            )

            assertEquals("RR-243.1", handler.resolveIdeIdentifier("env-1", "RR", "latest_eap"))
        }

    @Test
    fun `given empty available tools when resolving latest release then returns null`() = runTest(dispatcher) {
        coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns emptyList()

        assertNull(handler.resolveIdeIdentifier("env-1", "RR", "latest_release"))
    }

    @Test
    fun `given no matching release in feed when resolving latest release then falls back to max available tool`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-243.1", "RR-242.1")
            coEvery { feedService.fetchReleaseFeed() } returns emptyList()

            assertEquals("RR-243.1", handler.resolveIdeIdentifier("env-1", "RR", "latest_release"))
        }

    @Test
    fun `given available tools intersects with release feed when resolving latest release then returns matched version`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-242.1", "RR-241.1")
            coEvery { feedService.fetchReleaseFeed() } returns listOf(
                Ide("RR", "251.1", "2025.1", IdeType.RELEASE),
                Ide("RR", "243.1", "2024.3", IdeType.RELEASE),
                Ide("RR", "242.1", "2024.2", IdeType.RELEASE)
            )

            assertEquals("RR-242.1", handler.resolveIdeIdentifier("env-1", "RR", "latest_release"))
        }

    @Test
    fun `given installed tools exist when resolving latest installed then returns max installed version`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf("RR-240.1", "RR-241.1")

            assertEquals("RR-241.1", handler.resolveIdeIdentifier("env-1", "RR", "latest_installed"))
        }

    @Test
    fun `given no installed tools but available tools exist when resolving latest installed then falls back to max available`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns emptyList()
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-243.1", "RR-242.1")

            assertEquals("RR-243.1", handler.resolveIdeIdentifier("env-1", "RR", "latest_installed"))
        }

    @Test
    fun `given no installed and no available tools when resolving latest installed then returns null`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns emptyList()
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns emptyList()

            assertNull(handler.resolveIdeIdentifier("env-1", "RR", "latest_installed"))
        }

    @Test
    fun `given specific build exists in installed tools but not in available then expect the installed match to be returned`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-241.1", "RR-242.1")
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf("RR-251.1", "RR-252.1")

            assertEquals("RR-251.1", handler.resolveIdeIdentifier("env-1", "RR", "251.1"))
        }

    @Test
    fun `given specific build exists in available tools but not in installed then expect the available match to be returned`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-241.1", "RR-242.1")
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf("RR-251.1", "RR-252.1")

            assertEquals("RR-241.1", handler.resolveIdeIdentifier("env-1", "RR", "241.1"))
        }

    @Test
    fun `given specific build does not exist in installed tools nor in the available tools then null should be returned`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-241.1", "RR-242.1")
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf("RR-251.1", "RR-252.1")

            assertNull(handler.resolveIdeIdentifier("env-1", "RR", "221.1"))
        }

    @Test
    fun `installed build takes precedence over available tools when matching a build number`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf(
                "RR-241.1.3",
                "RR-242.1"
            )
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf(
                "RR-241.1.2",
                "RR-252.1"
            )

            assertEquals("RR-241.1.2", handler.resolveIdeIdentifier("env-1", "RR", "241.1"))
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