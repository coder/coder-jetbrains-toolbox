package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.diagnostics.CoderLogger
import com.coder.toolbox.session.SessionId
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
        val logger = mockk<CoderLogger>(relaxed = true)
        val sessionId = SessionId.generate()
        val testScope = this
        every { context.cs } returns testScope
        every { context.logger } returns logger
        val action = Action(context, "Stop workspace") {
            error("stop failed")
        }.withCurrentSessionId { sessionId }

        action.run()
        advanceUntilIdle()

        verify(exactly = 1) {
            logger.logAndShowError(
                sessionId,
                "Error while running `Stop workspace`",
                "stop failed",
                any(),
            )
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `action failure resolves the session when the error is logged`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val logger = mockk<CoderLogger>(relaxed = true)
        val sessionId = SessionId.generate()
        var currentSessionId: SessionId? = sessionId
        val testScope = this
        every { context.cs } returns testScope
        every { context.logger } returns logger
        val action = Action(context, "Stop workspace") {
            currentSessionId = null
            error("stop failed")
        }.withCurrentSessionId { currentSessionId }

        action.run()
        advanceUntilIdle()

        verify(exactly = 1) {
            logger.logAndShowError(
                null,
                "Error while running `Stop workspace`",
                "stop failed",
                any(),
            )
        }
    }
}
