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

private fun withSessionId(sessionId: SessionId, message: String): String =
    "$CLIENT_SESSION_ID_LOG_KEY=$sessionId $message"

/**
 * The plugin's single logging entry point.
 *
 * Calls without a [SessionId] are delegated unchanged. Calls with a session ID add the correlation
 * field to the log message.
 */
class CoderLogger(
    private val delegate: Logger,
    private val ui: ToolboxUi,
    private val cs: CoroutineScope,
    private val i18n: LocalizableStringFactory,
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
}
