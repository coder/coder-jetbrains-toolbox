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
}
