@file:Suppress("NOTHING_TO_INLINE", "OVERRIDE_BY_INLINE")

package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.coder.toolbox.settings.ReadOnlyCoderSettings
import com.jetbrains.toolbox.api.core.diagnostics.LogLevel
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
    settings: ReadOnlyCoderSettings,
) : Logger by delegate {
    private val flightRecorder = FlightRecorder(settings, cs, delegate)

    override inline fun error(message: String) {
        record(LogLevel.ERROR, message)
        delegate.error(message)
    }

    override inline fun error(noinline message: () -> String) {
        delegate.error(record(LogLevel.ERROR, null, message))
    }

    override inline fun error(exception: Throwable, message: String) {
        record(LogLevel.ERROR, message, exception)
        delegate.error(exception, message)
    }

    override inline fun error(exception: Throwable, noinline message: () -> String) {
        delegate.error(exception, record(LogLevel.ERROR, exception, message))
    }

    inline fun error(sessionId: SessionId?, exception: Throwable, message: String) {
        error(exception, withSessionId(sessionId, message))
    }

    inline fun error(sessionIds: Set<SessionId>, exception: Throwable, message: String) {
        sessionIds.onceOrForEach { error(exception, withSessionId(it, message)) }
    }

    override inline fun warn(message: String) {
        record(LogLevel.WARN, message)
        delegate.warn(message)
    }

    override inline fun warn(noinline message: () -> String) {
        delegate.warn(record(LogLevel.WARN, null, message))
    }

    override inline fun warn(exception: Throwable, message: String) {
        record(LogLevel.WARN, message, exception)
        delegate.warn(exception, message)
    }

    override inline fun warn(exception: Throwable, noinline message: () -> String) {
        delegate.warn(exception, record(LogLevel.WARN, exception, message))
    }

    inline fun warn(sessionId: SessionId?, message: String) {
        warn(withSessionId(sessionId, message))
    }

    inline fun warn(sessionId: SessionId?, exception: Throwable, message: String) {
        warn(exception, withSessionId(sessionId, message))
    }

    inline fun warn(sessionIds: Set<SessionId>, exception: Throwable, message: String) {
        sessionIds.onceOrForEach { warn(exception, withSessionId(it, message)) }
    }

    override inline fun debug(message: String) {
        record(LogLevel.DEBUG, message)
        delegate.debug(message)
    }

    override inline fun debug(noinline message: () -> String) {
        delegate.debug(record(LogLevel.DEBUG, null, message))
    }

    override inline fun debug(exception: Throwable, message: String) {
        record(LogLevel.DEBUG, message, exception)
        delegate.debug(exception, message)
    }

    override inline fun debug(exception: Throwable, noinline message: () -> String) {
        delegate.debug(exception, record(LogLevel.DEBUG, exception, message))
    }

    inline fun debug(sessionId: SessionId?, message: String) {
        debug(withSessionId(sessionId, message))
    }

    inline fun debug(sessionIds: Set<SessionId>, message: String) {
        sessionIds.onceOrForEach { debug(withSessionId(it, message)) }
    }

    override inline fun info(message: String) {
        record(LogLevel.INFO, message)
        delegate.info(message)
    }

    override inline fun info(noinline message: () -> String) {
        delegate.info(record(LogLevel.INFO, null, message))
    }

    override inline fun info(exception: Throwable, message: String) {
        record(LogLevel.INFO, message, exception)
        delegate.info(exception, message)
    }

    override inline fun info(exception: Throwable, noinline message: () -> String) {
        delegate.info(exception, record(LogLevel.INFO, exception, message))
    }

    inline fun info(sessionId: SessionId?, message: String) {
        info(withSessionId(sessionId, message))
    }

    inline fun info(sessionIds: Set<SessionId>, message: String) {
        sessionIds.onceOrForEach { info(withSessionId(it, message)) }
    }

    override inline fun trace(message: String) {
        record(LogLevel.TRACE, message)
        delegate.trace(message)
    }

    override inline fun trace(noinline message: () -> String) {
        delegate.trace(record(LogLevel.TRACE, null, message))
    }

    @Deprecated("Do not trace with exceptions", level = DeprecationLevel.ERROR)
    @Suppress("DEPRECATION_ERROR")
    override inline fun trace(exception: Throwable, message: String) {
        record(LogLevel.TRACE, message, exception)
        delegate.trace(exception, message)
    }

    @Deprecated("Do not trace with exceptions", level = DeprecationLevel.ERROR)
    @Suppress("DEPRECATION_ERROR")
    override inline fun trace(exception: Throwable, noinline message: () -> String) {
        delegate.trace(exception, record(LogLevel.TRACE, exception, message))
    }

    inline fun logAndShowError(title: String, error: String) {
        error(error)
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(sessionId: SessionId?, title: String, error: String) {
        error(withSessionId(sessionId, error))
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(title: String, error: String, exception: Throwable) {
        error(exception, error)
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(sessionId: SessionId?, title: String, error: String, exception: Throwable) {
        error(exception, withSessionId(sessionId, error))
        showInfoPopup(title, error)
    }

    inline fun logAndShowError(
        sessionIds: Set<SessionId>,
        title: String,
        error: String,
        exception: Throwable,
    ) {
        sessionIds.onceOrForEach { error(exception, withSessionId(it, error)) }
        showInfoPopup(title, error)
    }

    inline fun logAndShowWarning(title: String, warning: String) {
        warn(warning)
        showInfoPopup(title, warning)
    }

    inline fun logAndShowWarning(sessionId: SessionId?, title: String, warning: String) {
        warn(withSessionId(sessionId, warning))
        showInfoPopup(title, warning)
    }

    inline fun logAndShowWarning(
        sessionIds: Set<SessionId>,
        title: String,
        warning: String,
        exception: Throwable,
    ) {
        sessionIds.onceOrForEach { warn(exception, withSessionId(it, warning)) }
        showInfoPopup(title, warning)
    }

    inline fun logAndShowWarning(title: String, warning: String, exception: Throwable) {
        warn(exception, warning)
        showInfoPopup(title, warning)
    }

    inline fun logAndShowInfo(title: String, info: String) {
        info(info)
        showInfoPopup(title, info)
    }

    @PublishedApi
    internal fun record(level: LogLevel, message: String, exception: Throwable? = null) {
        flightRecorder.record(level, message, exception)
    }

    @PublishedApi
    internal fun record(level: LogLevel, exception: Throwable?, message: () -> String): () -> String =
        flightRecorder.record(level, exception, message)

    /** Replays and removes buffered logs, oldest first, with an optional session prefix on the header and footer. */
    fun flush(reason: String, sessionId: SessionId? = null) {
        flightRecorder.flush(reason, sessionId)
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
                error(ex, "Failed to display popup with title '$title'")
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
