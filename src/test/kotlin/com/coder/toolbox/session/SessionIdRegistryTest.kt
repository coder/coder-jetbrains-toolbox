package com.coder.toolbox.session

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.diagnostics.CoderLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionIdRegistryTest {
    private val logger = mockk<CoderLogger>(relaxed = true)
    private val context = mockk<CoderToolboxContext>(relaxed = true)

    init {
        every { context.logger } returns logger
    }

    @Test
    fun `start session creates a correctly encoded id`() {
        val key = uniqueKey()
        val sessionId = SessionIdRegistry.startSession(context, key.workspaceName, key.agentName)

        assertTrue(sessionId.value.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun `start session reuses the id for the same workspace and agent`() {
        val key = uniqueKey()
        val first = SessionIdRegistry.startSession(context, key.workspaceName, key.agentName)
        val second = SessionIdRegistry.startSession(context, key.workspaceName, key.agentName)

        assertEquals(first, second)
        assertEquals(first, SessionIdRegistry.findSession(key.workspaceName, key.agentName))
        verify(exactly = 1) {
            logger.info(first, "Created Toolbox SSH session for ${key.workspaceName}.${key.agentName}")
        }
    }

    @Test
    fun `workspace and agent names both participate in the key`() {
        val suffix = UUID.randomUUID().toString()
        val workspaceOne = "workspace-one-$suffix"
        val workspaceTwo = "workspace-two-$suffix"
        val agentOne = "agent-one-$suffix"
        val agentTwo = "agent-two-$suffix"
        val first = SessionIdRegistry.startSession(context, workspaceOne, agentOne)
        val differentWorkspace = SessionIdRegistry.startSession(context, workspaceTwo, agentOne)
        val differentAgent = SessionIdRegistry.startSession(context, workspaceOne, agentTwo)

        assertNotEquals(first, differentWorkspace)
        assertNotEquals(first, differentAgent)
    }

    @Test
    fun `finding a missing session does not create one`() {
        val key = uniqueKey()

        assertNull(SessionIdRegistry.findSession(key.workspaceName, key.agentName))
    }

    @Test
    fun `removing a session gives the next connection a new id`() {
        val key = uniqueKey()
        val removedSession = SessionIdRegistry.startSession(context, key.workspaceName, key.agentName)

        assertEquals(removedSession, SessionIdRegistry.removeSession(key.workspaceName, key.agentName))

        val replacementSession = SessionIdRegistry.startSession(context, key.workspaceName, key.agentName)
        assertNotEquals(removedSession, replacementSession)
    }

    @Test
    fun `concurrent starts create only one session`() = runTest {
        val key = uniqueKey()
        val results = List(100) {
            async(Dispatchers.Default) {
                SessionIdRegistry.startSession(context, key.workspaceName, key.agentName)
            }
        }.awaitAll()

        assertEquals(1, results.toSet().size)
        verify(exactly = 1) {
            logger.info(results.first(), "Created Toolbox SSH session for ${key.workspaceName}.${key.agentName}")
        }
    }

    private fun uniqueKey(): TestSessionKey {
        val suffix = UUID.randomUUID().toString()
        return TestSessionKey("workspace-$suffix", "agent-$suffix")
    }

    private data class TestSessionKey(
        val workspaceName: String,
        val agentName: String,
    )
}
