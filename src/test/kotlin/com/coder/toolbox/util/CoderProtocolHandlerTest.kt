package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.feed.Ide
import com.coder.toolbox.feed.IdeFeedManager
import com.coder.toolbox.feed.IdeType
import com.coder.toolbox.feed.JetBrainsFeedService
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CoderProtocolHandlerTest {

    private lateinit var context: CoderToolboxContext
    private lateinit var feedService: JetBrainsFeedService
    private lateinit var ideFeedManager: IdeFeedManager
    private lateinit var handler: CoderProtocolHandler
    private lateinit var remoteToolsHelper: RemoteToolsHelper

    // Test Coroutine Scope
    private val dispatcher = StandardTestDispatcher()

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
    fun `given specific build exists in available tools but not in installed then expect the available match to be returned`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-241.1", "RR-242.1")
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf("RR-251.1", "RR-252.1")

            assertEquals("RR-241.1", handler.resolveIdeIdentifier("env-1", "RR", "241.1"))
        }

    @Test
    fun `given specific build exists in installed tools but not in available then expect the installed match to be returned`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-241.1", "RR-242.1")
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf("RR-251.1", "RR-252.1")

            assertEquals("RR-251.1", handler.resolveIdeIdentifier("env-1", "RR", "251.1"))
        }

    @Test
    fun `given specific build does not exist in installed tools nor in the  available tools then null should be returned`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-241.1", "RR-242.1")
            coEvery { remoteToolsHelper.getInstalledRemoteTools("env-1", "RR") } returns listOf("RR-251.1", "RR-252.1")

            assertNull(handler.resolveIdeIdentifier("env-1", "RR", "221.1"))
        }

    @Test
    fun `given specific build does not exist in available tools when resolving specific build then constructs identifier`() =
        runTest(dispatcher) {
            coEvery { remoteToolsHelper.getAvailableRemoteTools("env-1", "RR") } returns listOf("RR-242.1")

            assertEquals("RR-200.1", handler.resolveIdeIdentifier("env-1", "RR", "200.1"))
        }
}