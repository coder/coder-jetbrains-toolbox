package com.coder.toolbox.session

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
 * Process-local registry of active connection sessions.
 *
 * A session is keyed only by workspace and agent names. Call [startSession] from the initial SSH
 * connection path; all other code should use [findSession] so observing a session cannot create one.
 * Entries intentionally remain across SSH disconnects and reconnects. Call [removeSession] only
 * when the Toolbox environment that owns the session is disposed.
 */
object SessionIdRegistry {
    private val sessionIds = ConcurrentHashMap<SessionKey, SessionId>()

    /**
     * Returns the active session ID for this workspace and agent, creating it when absent.
     *
     * Reusing an existing ID allows transient reconnects to remain part of the same session.
     */
    fun startSession(workspaceName: String, agentName: String): SessionId =
        sessionIds.computeIfAbsent(SessionKey(workspaceName, agentName)) { SessionId.generate() }

    /** Returns the active session ID without creating a session. */
    fun findSession(workspaceName: String, agentName: String): SessionId? =
        sessionIds[SessionKey(workspaceName, agentName)]

    /**
     * Removes the session when its owning Toolbox environment is disposed.
     *
     * This must only be called from the environment disposal lifecycle, such as
     * `RemoteEnvironment.dispose()`, when Toolbox removes or destroys that environment. It must
     * not be called when an IDE closes, the SSH transport disconnects, or the SSH transport reconnects;
     * those events remain part of the same Toolbox session.
     */
    fun removeSession(workspaceName: String, agentName: String): SessionId? =
        sessionIds.remove(SessionKey(workspaceName, agentName))
}
