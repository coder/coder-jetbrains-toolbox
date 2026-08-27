package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class CoderLoggerTest {
    private val delegate = mockk<Logger>(relaxed = true)
    private val showInfoPopup = mockk<(String, String) -> Unit>(relaxed = true)
    private val logger = CoderLogger(delegate, showInfoPopup)
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
    fun `session-aware logs include the client session id`() {
        logger.error(sessionId, "error")
        logger.warn(sessionId, "warning")
        logger.debug(sessionId, "debug")
        logger.info(sessionId, "info")

        verify(exactly = 1) { delegate.error("$prefix error") }
        verify(exactly = 1) { delegate.warn("$prefix warning") }
        verify(exactly = 1) { delegate.debug("$prefix debug") }
        verify(exactly = 1) { delegate.info("$prefix info") }
    }

    @Test
    fun `log and show logs and displays the same user message`() {
        logger.logAndShowInfo("Connection ready", "Connected to the workspace")

        verify(exactly = 1) { delegate.info("Connected to the workspace") }
        verify(exactly = 1) { showInfoPopup("Connection ready", "Connected to the workspace") }
    }

    @Test
    fun `sessionless log and show remains unchanged`() {
        val exception = IllegalStateException("failed")

        logger.logAndShowError("Connection failed", "Could not connect", exception)

        verify(exactly = 1) { delegate.error(exception, "Could not connect") }
        verify(exactly = 1) { showInfoPopup("Connection failed", "Could not connect") }
    }
}
