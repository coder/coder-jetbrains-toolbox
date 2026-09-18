package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.diagnostics.CoderProviderLogCollector
import com.coder.toolbox.diagnostics.LogCollectionProgress
import com.jetbrains.toolbox.api.ui.components.CallToActionField
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewEnvironmentPageDiagnosticsTest {
    private fun header(context: CoderToolboxContext) = NewEnvironmentPage(
        context, context.i18n.pnotr("Coder"), Channel(Channel.CONFLATED), { emptyList() }
    )

    @Test
    fun `repeated requests collect once and restore only the filters`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val scope = this
        every { context.cs } returns scope
        val collector = mockk<CoderProviderLogCollector>()
        val archive = Path.of("logs.zip")
        coEvery { collector.collect(any()) } returns archive
        val page = header(context)
        val filters = page.fields.value
        page.setFilter("owner:someone status:running")

        page.collectDiagnostics(collector)
        page.collectDiagnostics(collector)
        assertTrue(page.isBusy.value)
        assertTrue(page.isCancellable.value)
        advanceUntilIdle()

        coVerify(exactly = 1) { collector.collect(any()) }
        verify(exactly = 1) { context.desktop.openPath(archive) }
        assertFalse(page.isBusy.value)
        assertFalse(page.isCancellable.value)
        assertEquals(filters, page.fields.value)
        assertEquals("owner:someone status:running", page.workspaceSearchQuery.value)

    }

    @Test
    fun `cancel button stays enabled during collection and stops it without revealing a partial archive`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val scope = this
        every { context.cs } returns scope
        val collector = mockk<CoderProviderLogCollector>()
        val logger = context.logger.delegate
        var stopped = false
        coEvery { collector.collect(any()) } coAnswers {
            try {
                firstArg<(LogCollectionProgress) -> Unit>()(
                    LogCollectionProgress("Collecting workspace diagnostics…", "1/2: owner/workspace.agent")
                )
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val page = header(context)
        val filters = page.fields.value
        page.setFilter("owner:someone status:running")

        page.collectDiagnostics(collector)
        runCurrent()
        assertTrue(page.isBusy.value)
        assertTrue(page.isCancellable.value)
        page.afterHide()
        page.beforeShow()
        runCurrent()
        coVerify(exactly = 1) { collector.collect(any()) }
        val cancelButton = page.fields.value.filterIsInstance<CallToActionField>().single().actionState.value
        assertTrue(cancelButton.isEnabled)
        assertTrue(cancelButton.validate())
        verify { context.i18n.ptrl("Cancel") }
        verify { context.i18n.ptrl("Collecting workspace diagnostics…") }
        verify { context.i18n.pnotr("1/2: owner/workspace.agent") }
        cancelButton.run()
        advanceUntilIdle()

        page.afterHide()
        assertTrue(stopped)
        assertFalse(page.isBusy.value)
        assertFalse(page.isCancellable.value)
        assertEquals(filters, page.fields.value)
        assertEquals("owner:someone status:running", page.workspaceSearchQuery.value)
        verify(exactly = 0) { context.desktop.openPath(any()) }
        verify(exactly = 0) { logger.warn(any<Throwable>(), any<String>()) }
    }

    @Test
    fun `cancelling before the collection starts restores filters`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val scope = this
        every { context.cs } returns scope
        val collector = mockk<CoderProviderLogCollector>()
        val page = header(context)
        val filters = page.fields.value
        page.collectDiagnostics(collector)
        assertTrue(page.isBusy.value)
        page.cancel()
        advanceUntilIdle()
        coVerify(exactly = 0) { collector.collect(any()) }
        assertEquals(filters, page.fields.value)
        assertFalse(page.isBusy.value)
        assertFalse(page.isCancellable.value)
    }

    @Test
    fun `archive failure restores filters with an error message`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val scope = this
        every { context.cs } returns scope
        val collector = mockk<CoderProviderLogCollector>()
        coEvery { collector.collect(any()) } throws IllegalStateException("disk unavailable")
        val page = header(context)
        val filters = page.fields.value
        page.setFilter("owner:someone status:running")
        page.collectDiagnostics(collector)
        advanceUntilIdle()
        assertFalse(page.isBusy.value)
        assertFalse(page.isCancellable.value)
        assertEquals(filters, page.fields.value.drop(1))
        assertEquals("owner:someone status:running", page.workspaceSearchQuery.value)
        verify(exactly = 0) { context.desktop.openPath(any()) }
    }
}
