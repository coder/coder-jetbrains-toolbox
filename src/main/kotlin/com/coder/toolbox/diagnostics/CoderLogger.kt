@file:Suppress("NOTHING_TO_INLINE", "OVERRIDE_BY_INLINE")

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

@PublishedApi
internal fun withSessionId(sessionId: SessionId?, message: String): String =
    sessionId?.let { "$CLIENT_SESSION_ID_LOG_KEY=$it $message" } ?: message

/**
 * The plugin's single logging entry point.
 *
 * A null [SessionId] leaves the message unchanged. A non-null ID adds the correlation field, while
 * a set of IDs emits one log for each session (or one unchanged log when the set is empty).
 * Logging methods are inline so the Toolbox logger identifies the business call site rather than
 * this wrapper as the source of each message.
 */
class CoderLogger(
    @PublishedApi internal val delegate: Logger,
    private val ui: ToolboxUi,
    private val cs: CoroutineScope,
    private val i18n: LocalizableStringFactory,
) : Logger by delegate {
    override inline fun error(message: String) {
        delegate.error(message)
    }

    override inline fun error(exception: Throwable, message: String) {
        delegate.error(exception, message)
    }

    inline fun error(sessionId: SessionId?, exception: Throwable, message: String) {
        delegate.error(exception, withSessionId(sessionId, message))
    }

    inline fun error(sessionIds: Set<SessionId>, exception: Throwable, message: String) {
        sessionIds.onceOrForEach { delegate.error(exception, withSessionId(it, message)) }
    }

    override inline fun warn(message: String) {
        delegate.warn(message)
    }

    override inline fun warn(exception: Throwable, message: String) {
        delegate.warn(exception, message)
    }

    inline fun warn(sessionId: SessionId?, message: String) {
        delegate.warn(withSessionId(sessionId, message))
    }

    inline fun warn(sessionIds: Set<SessionId>, exception: Throwable, message: String) {
        sessionIds.onceOrForEach { delegate.warn(exception, withSessionId(it, message)) }
    }

    override inline fun debug(message: String) {
        delegate.debug(message)
    }

    inline fun debug(sessionId: SessionId?, message: String) {
        delegate.debug(withSessionId(sessionId, message))
    }

    inline fun debug(sessionIds: Set<SessionId>, message: String) {
        sessionIds.onceOrForEach { delegate.debug(withSessionId(it, message)) }
    }

    override inline fun info(message: String) {
        delegate.info(message)
    }

    inline fun info(sessionId: SessionId?, message: String) {
        delegate.info(withSessionId(sessionId, message))
    }

    inline fun info(sessionIds: Set<SessionId>, message: String) {
        sessionIds.onceOrForEach { delegate.info(withSessionId(it, message)) }
    }

    inline fun logAndShowError(title: String, error: String) {
        delegate.error(error)
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(sessionId: SessionId?, title: String, error: String) {
        delegate.error(withSessionId(sessionId, error))
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(title: String, error: String, exception: Throwable) {
        delegate.error(exception, error)
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(sessionId: SessionId?, title: String, error: String, exception: Throwable) {
        delegate.error(exception, withSessionId(sessionId, error))
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(
        sessionIds: Set<SessionId>,
        title: String,
        error: String,
        exception: Throwable,
    ) {
        sessionIds.onceOrForEach { delegate.error(exception, withSessionId(it, error)) }
        showInfoPopup(title, error)
    }

    inline fun logAndShowWarning(title: String, warning: String) {
        delegate.warn(warning)
        showInfoPopup(title, warning)
    }

    inline fun logAndShowWarning(sessionId: SessionId?, title: String, warning: String) {
        delegate.warn(withSessionId(sessionId, warning))
        showInfoPopup(title, warning)
    }

    inline fun logAndShowWarning(
        sessionIds: Set<SessionId>,
        title: String,
        warning: String,
        exception: Throwable,
    ) {
        sessionIds.onceOrForEach { delegate.warn(exception, withSessionId(it, warning)) }
        showInfoPopup(title, warning)
    }

    inline fun logAndShowWarning(title: String, warning: String, exception: Throwable) {
        delegate.warn(exception, warning)
        showInfoPopup(title, warning)
    }

    inline fun logAndShowInfo(title: String, info: String) {
        delegate.info(info)
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
    @PublishedApi
    internal fun showInfoPopup(title: String, text: String) {
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
                delegate.error(ex, "Failed to display popup with title '$title'")
            }
        }
    }

    @PublishedApi
    internal inline fun Set<SessionId>.onceOrForEach(action: (SessionId?) -> Unit) {
        if (isEmpty()) {
            action(null)
        } else {
            forEach(action)
        }
    }
}
