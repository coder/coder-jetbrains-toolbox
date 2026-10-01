package com.coder.toolbox.session

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.util.toHex
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private const val SESSION_ID_BYTE_LENGTH = 16
private val SESSION_ID_PATTERN = Regex("^[0-9a-f]{32}$")

/**
 * Identifies one client-managed connection session.
 *
 * Session IDs are 16-byte values encoded as 32 lowercase hexadecimal characters.
 */
@JvmInline
value class SessionId private constructor(val value: String) {
    init {
        require(SESSION_ID_PATTERN.matches(value)) { "Session ID must be a 32-character lowercase hexadecimal string" }
    }

    override fun toString(): String = value

    companion object {
        internal fun generate(): SessionId {
            val bytes = ByteArray(SESSION_ID_BYTE_LENGTH)
            SecureRandomHolder.instance.nextBytes(bytes)
            return SessionId(bytes.toHex())
        }
    }
}

private object SecureRandomHolder {
    val instance = SecureRandom()
}

private data class SessionKey(
    val workspaceName: String,
    val agentName: String,
)

/**
 * Process-local registry of connection sessions.
 *
 * A session is keyed only by workspace and agent names. Call [startSession] from the SSH
 * before-connection callback; all other code should use [findSession] so observing a session cannot
 * create one. Non-user disconnects and retries remain part of the same logical session. A manual
 * disconnect ends the session, as does disposal of the Toolbox environment that owns it.
 */
object SessionIdRegistry {
    private val sessionIds = ConcurrentHashMap<SessionKey, SessionId>()

    /**
     * Returns the session ID for this workspace and agent, creating it when absent.
     *
     * Reusing an existing ID allows transient reconnects to remain part of the same session. A
     * newly created session is logged here so callers do not need to distinguish creation from reuse.
     */
    fun startSession(context: CoderToolboxContext, workspaceName: String, agentName: String): SessionId {
        var created = false
        val sessionId = sessionIds.computeIfAbsent(SessionKey(workspaceName, agentName)) {
            created = true
            SessionId.generate()
        }
        if (created) {
            context.logger.info(sessionId, "Created Toolbox SSH session for $workspaceName.$agentName")
        }
        return sessionId
    }

    /** Returns the current logical session ID without creating one. */
    fun findSession(workspaceName: String, agentName: String): SessionId? =
        sessionIds[SessionKey(workspaceName, agentName)]

    /**
     * Removes a session that has reached the end of its lifetime.
     *
     * Call this when the user deliberately disconnects or when Toolbox disposes the environment.
     * Do not call it for a transient transport disconnect or an IDE closing; those events remain
     * part of the same Toolbox session.
     */
    fun removeSession(workspaceName: String, agentName: String): SessionId? =
        sessionIds.remove(SessionKey(workspaceName, agentName))
}
