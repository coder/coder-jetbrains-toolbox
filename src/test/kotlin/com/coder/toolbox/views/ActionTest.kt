package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.diagnostics.CoderLogger
import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ActionTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `action failure uses the current session`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val sessionId = SessionId.generate()
        val testScope = this
        val coderLogger = CoderLogger(
            logger,
            mockk<ToolboxUi>(relaxed = true),
            testScope,
            mockk<LocalizableStringFactory>(relaxed = true),
        )
        every { context.cs } returns testScope
        every { context.logger } returns coderLogger
        val action = Action(context, "Stop workspace") {
            error("stop failed")
        }.withCurrentSessionId { sessionId }

        action.run()
        advanceUntilIdle()

        verify(exactly = 1) {
            logger.error(any<Throwable>(), "client_session_id=$sessionId stop failed")
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `action failure resolves the session when the error is logged`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val sessionId = SessionId.generate()
        var currentSessionId: SessionId? = sessionId
        val testScope = this
        val coderLogger = CoderLogger(
            logger,
            mockk<ToolboxUi>(relaxed = true),
            testScope,
            mockk<LocalizableStringFactory>(relaxed = true),
        )
        every { context.cs } returns testScope
        every { context.logger } returns coderLogger
        val action = Action(context, "Stop workspace") {
            currentSessionId = null
            error("stop failed")
        }.withCurrentSessionId { currentSessionId }

        action.run()
        advanceUntilIdle()

        verify(exactly = 1) {
            logger.error(any<Throwable>(), "stop failed")
        }
    }
}
