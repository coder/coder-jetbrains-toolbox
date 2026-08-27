package com.coder.toolbox.session

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
    @Test
    fun `start session creates a correctly encoded id`() {
        val key = uniqueKey()
        val sessionId = SessionIdRegistry.startSession(key.workspaceName, key.agentName)

        assertTrue(sessionId.value.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun `start session reuses the active id for the same workspace and agent`() {
        val key = uniqueKey()
        val first = SessionIdRegistry.startSession(key.workspaceName, key.agentName)
        val second = SessionIdRegistry.startSession(key.workspaceName, key.agentName)

        assertEquals(first, second)
        assertEquals(first, SessionIdRegistry.findSession(key.workspaceName, key.agentName))
    }

    @Test
    fun `workspace and agent names both participate in the key`() {
        val suffix = UUID.randomUUID().toString()
        val workspaceOne = "workspace-one-$suffix"
        val workspaceTwo = "workspace-two-$suffix"
        val agentOne = "agent-one-$suffix"
        val agentTwo = "agent-two-$suffix"
        val first = SessionIdRegistry.startSession(workspaceOne, agentOne)
        val differentWorkspace = SessionIdRegistry.startSession(workspaceTwo, agentOne)
        val differentAgent = SessionIdRegistry.startSession(workspaceOne, agentTwo)

        assertNotEquals(first, differentWorkspace)
        assertNotEquals(first, differentAgent)
    }

    @Test
    fun `finding a missing session does not create one`() {
        val key = uniqueKey()

        assertNull(SessionIdRegistry.findSession(key.workspaceName, key.agentName))
    }

    @Test
    fun `disposing an environment removes its session`() {
        val key = uniqueKey()
        val disposedSession = SessionIdRegistry.startSession(key.workspaceName, key.agentName)

        assertEquals(disposedSession, SessionIdRegistry.removeSession(key.workspaceName, key.agentName))
        assertNull(SessionIdRegistry.findSession(key.workspaceName, key.agentName))

        val replacementSession = SessionIdRegistry.startSession(key.workspaceName, key.agentName)
        assertNotEquals(disposedSession, replacementSession)
    }

    @Test
    fun `concurrent starts create only one session`() = runTest {
        val key = uniqueKey()
        val sessions = List(100) {
            async(Dispatchers.Default) {
                SessionIdRegistry.startSession(key.workspaceName, key.agentName)
            }
        }.awaitAll()

        assertEquals(1, sessions.toSet().size)
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
