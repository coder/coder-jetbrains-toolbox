package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.core.diagnostics.Logger

private const val CLIENT_SESSION_ID_LOG_KEY = "client_session_id"

/**
 * The plugin's single logging entry point.
 *
 * Calls without a [SessionId] are delegated unchanged. Calls with a session ID add the correlation
 * field to the log message.
 */
class CoderLogger(
    private val delegate: Logger,
    private val showInfoPopup: (title: String, text: String) -> Unit,
) : Logger by delegate {
    fun error(sessionId: SessionId, message: String) {
        delegate.error(withSessionId(sessionId, message))
    }

    fun warn(sessionId: SessionId, message: String) {
        delegate.warn(withSessionId(sessionId, message))
    }

    fun debug(sessionId: SessionId, message: String) {
        delegate.debug(withSessionId(sessionId, message))
    }

    fun info(sessionId: SessionId, message: String) {
        delegate.info(withSessionId(sessionId, message))
    }

    fun logAndShowError(title: String, error: String) {
        error(error)
        showInfoPopup(title, error)
    }

    fun logAndShowError(title: String, error: String, exception: Throwable) {
        error(exception, error)
        showInfoPopup(title, error)
    }

    fun logAndShowWarning(title: String, warning: String) {
        warn(warning)
        showInfoPopup(title, warning)
    }

    fun logAndShowWarning(title: String, warning: String, exception: Throwable) {
        warn(exception, warning)
        showInfoPopup(title, warning)
    }

    fun logAndShowInfo(title: String, info: String) {
        info(info)
        showInfoPopup(title, info)
    }

    private fun withSessionId(sessionId: SessionId, message: String): String =
        "$CLIENT_SESSION_ID_LOG_KEY=$sessionId $message"
}
