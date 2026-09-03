package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.ToolboxUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val CLIENT_SESSION_ID_LOG_KEY = "client_session_id"

private fun withSessionId(sessionId: SessionId?, message: String): String =
    sessionId?.let { "$CLIENT_SESSION_ID_LOG_KEY=$it $message" } ?: message

/**
 * The plugin's single logging entry point.
 *
 * A null [SessionId] leaves the message unchanged. A non-null ID adds the correlation field, while
 * a set of IDs emits one log for each session (or one unchanged log when the set is empty).
 */
class CoderLogger(
    private val delegate: Logger,
    private val ui: ToolboxUi,
    private val cs: CoroutineScope,
    private val i18n: LocalizableStringFactory,
) : Logger by delegate {
    fun error(sessionId: SessionId?, exception: Throwable, message: String) {
        delegate.error(exception, withSessionId(sessionId, message))
    }

    fun error(sessionIds: Set<SessionId>, exception: Throwable, message: String) {
        sessionIds.onceOrForEach { error(it, exception, message) }
    }

    fun warn(sessionId: SessionId?, message: String) {
        delegate.warn(withSessionId(sessionId, message))
    }

    fun warn(sessionIds: Set<SessionId>, exception: Throwable, message: String) {
        sessionIds.onceOrForEach { delegate.warn(exception, withSessionId(it, message)) }
    }

    fun debug(sessionId: SessionId?, message: String) {
        delegate.debug(withSessionId(sessionId, message))
    }

    fun debug(sessionIds: Set<SessionId>, message: String) {
        sessionIds.onceOrForEach { debug(it, message) }
    }

    fun info(sessionId: SessionId?, message: String) {
        delegate.info(withSessionId(sessionId, message))
    }

    fun info(sessionIds: Set<SessionId>, message: String) {
        sessionIds.onceOrForEach { info(it, message) }
    }

    fun logAndShowError(title: String, error: String) {
        error(error)
        showInfoPopup(title, error)
    }

    fun logAndShowError(sessionId: SessionId?, title: String, error: String) {
        delegate.error(withSessionId(sessionId, error))
        showInfoPopup(title, error)
    }

    fun logAndShowError(title: String, error: String, exception: Throwable) {
        error(exception, error)
        showInfoPopup(title, error)
    }

    fun logAndShowError(sessionId: SessionId?, title: String, error: String, exception: Throwable) {
        error(sessionId, exception, error)
        showInfoPopup(title, error)
    }

    fun logAndShowError(
        sessionIds: Set<SessionId>,
        title: String,
        error: String,
        exception: Throwable,
    ) {
        sessionIds.onceOrForEach { this.error(it, exception, error) }
        showInfoPopup(title, error)
    }

    fun logAndShowWarning(title: String, warning: String) {
        warn(warning)
        showInfoPopup(title, warning)
    }

    fun logAndShowWarning(sessionId: SessionId?, title: String, warning: String) {
        warn(sessionId, warning)
        showInfoPopup(title, warning)
    }

    fun logAndShowWarning(
        sessionIds: Set<SessionId>,
        title: String,
        warning: String,
        exception: Throwable,
    ) {
        sessionIds.onceOrForEach { delegate.warn(exception, withSessionId(it, warning)) }
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

    /**
     * Displays an informational popup on a child of the plugin coroutine scope rather than on
     * the caller's coroutine, without waiting for it.
     *
     * Unlike [ToolboxUi.showSnackbar], a popup is backed by a persistent dialog state: it is
     * still rendered once the window becomes visible even if it was requested while the window
     * was hidden, it is not silently dropped when several are requested, and dismissing it
     * resumes the [ToolboxUi.showInfoPopup] coroutine normally instead of cancelling it.
     *
     * It is launched fire-and-forget so the caller is not suspended until the user closes the
     * popup. The caller can run any follow-up work immediately.
     */
    private fun showInfoPopup(title: String, text: String) {
        cs.launch(CoroutineName("popup")) {
            try {
                ui.showInfoPopup(
                    i18n.pnotr(title),
                    i18n.pnotr(text),
                    i18n.ptrl("OK")
                )
            } catch (_: CancellationException) {
                // Expected when the plugin scope shuts down while the popup is open.
            } catch (ex: Exception) {
                error(ex, "Failed to display popup with title '$title'")
            }
        }
    }

    private inline fun Set<SessionId>.onceOrForEach(action: (SessionId?) -> Unit) {
        if (isEmpty()) {
            action(null)
        } else {
            forEach(action)
        }
    }
}
