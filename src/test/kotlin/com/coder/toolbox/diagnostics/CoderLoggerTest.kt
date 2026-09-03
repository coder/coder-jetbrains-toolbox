package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

class CoderLoggerTest {
    private val delegate = mockk<Logger>(relaxed = true)
    private val ui = mockk<ToolboxUi>(relaxed = true)
    private val i18n = mockk<LocalizableStringFactory>(relaxed = true)
    private val logger = CoderLogger(delegate, ui, CoroutineScope(Dispatchers.Unconfined), i18n)
    private val sessionId = SessionId.generate()
    private val prefix = "client_session_id=$sessionId"

    @Test
    fun `sessionless logs are delegated unchanged`() {
        val exception = IllegalStateException("failed")

        logger.info("connected")
        logger.error(exception, "connection failed")

        verify(exactly = 1) { delegate.info("connected") }
        verify(exactly = 1) { delegate.error(exception, "connection failed") }
    }

    @Test
    fun `nullable session logs are delegated unchanged when the id is null`() {
        val exception = IllegalStateException("failed")

        logger.error(null, exception, "exception")
        logger.warn(null, "warning")
        logger.debug(null, "debug")
        logger.info(null, "info")
        logger.info(message = "named info")
        logger.error(exception = exception, message = "named exception")

        verify(exactly = 1) { delegate.error(exception, "exception") }
        verify(exactly = 1) { delegate.warn("warning") }
        verify(exactly = 1) { delegate.debug("debug") }
        verify(exactly = 1) { delegate.info("info") }
        verify(exactly = 1) { delegate.info("named info") }
        verify(exactly = 1) { delegate.error(exception, "named exception") }
    }

    @Test
    fun `session-aware logs include the client session id`() {
        val exception = IllegalStateException("failed")

        logger.error(sessionId, exception, "exception")
        logger.warn(sessionId, "warning")
        logger.debug(sessionId, "debug")
        logger.info(sessionId, "info")

        verify(exactly = 1) { delegate.error(exception, "$prefix exception") }
        verify(exactly = 1) { delegate.warn("$prefix warning") }
        verify(exactly = 1) { delegate.debug("$prefix debug") }
        verify(exactly = 1) { delegate.info("$prefix info") }
    }

    @Test
    fun `session sets fan out or emit one sessionless log when empty`() {
        val secondSessionId = SessionId.generate()
        val exception = IllegalStateException("failed")

        logger.info(setOf(sessionId, secondSessionId), "shared info")
        logger.debug(setOf(sessionId, secondSessionId), "shared debug")
        logger.error(emptySet(), exception, "shared error")

        verify(exactly = 1) { delegate.info("$prefix shared info") }
        verify(exactly = 1) { delegate.info("client_session_id=$secondSessionId shared info") }
        verify(exactly = 0) { delegate.info("shared info") }
        verify(exactly = 1) { delegate.debug("$prefix shared debug") }
        verify(exactly = 1) { delegate.debug("client_session_id=$secondSessionId shared debug") }
        verify(exactly = 0) { delegate.debug("shared debug") }
        verify(exactly = 1) { delegate.error(exception, "shared error") }
    }

    @Test
    fun `log and show logs and displays the same user message`() {
        logger.logAndShowInfo("Connection ready", "Connected to the workspace")

        verify(exactly = 1) { delegate.info("Connected to the workspace") }
        verify(exactly = 1) { i18n.pnotr("Connection ready") }
        verify(exactly = 1) { i18n.pnotr("Connected to the workspace") }
        coVerify(exactly = 1) {
            ui.showInfoPopup(
                any<LocalizableString>(),
                any<LocalizableString>(),
                any<LocalizableString>(),
            )
        }
    }

    @Test
    fun `sessionless log and show remains unchanged`() {
        val exception = IllegalStateException("failed")

        logger.logAndShowError("Connection failed", "Could not connect", exception)

        verify(exactly = 1) { delegate.error(exception, "Could not connect") }
        coVerify(exactly = 1) {
            ui.showInfoPopup(
                any<LocalizableString>(),
                any<LocalizableString>(),
                any<LocalizableString>(),
            )
        }
    }

    @Test
    fun `session-aware log and show correlates logs without changing popup text`() {
        val exception = IllegalStateException("failed")

        logger.logAndShowError(sessionId, "Connection failed", "Could not connect")
        logger.logAndShowError(sessionId, "Connection crashed", "The connection crashed", exception)
        logger.logAndShowWarning(sessionId, "Connection unstable", "The connection is unstable")

        verify(exactly = 1) { delegate.error("$prefix Could not connect") }
        verify(exactly = 1) { delegate.error(exception, "$prefix The connection crashed") }
        verify(exactly = 1) { delegate.warn("$prefix The connection is unstable") }
        verify(exactly = 1) { i18n.pnotr("Could not connect") }
        verify(exactly = 1) { i18n.pnotr("The connection crashed") }
        verify(exactly = 1) { i18n.pnotr("The connection is unstable") }
        coVerify(exactly = 3) {
            ui.showInfoPopup(
                any<LocalizableString>(),
                any<LocalizableString>(),
                any<LocalizableString>(),
            )
        }
    }

    @Test
    fun `session set log and show logs every session but displays one popup`() {
        val secondSessionId = SessionId.generate()
        val exception = IllegalStateException("failed")

        logger.logAndShowWarning(
            setOf(sessionId, secondSessionId),
            "Connection unstable",
            "The connection is unstable",
            exception,
        )

        verify(exactly = 1) { delegate.warn(exception, "$prefix The connection is unstable") }
        verify(exactly = 1) {
            delegate.warn(exception, "client_session_id=$secondSessionId The connection is unstable")
        }
        verify(exactly = 0) { delegate.warn(exception, "The connection is unstable") }
        coVerify(exactly = 1) {
            ui.showInfoPopup(
                any<LocalizableString>(),
                any<LocalizableString>(),
                any<LocalizableString>(),
            )
        }
    }
}
