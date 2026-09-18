package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.diagnostics.CoderProviderLogCollector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoderDiagnosticCollectorPageTest {
    @Test
    fun `showing the page repeatedly collects once and reveals the completed archive`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val scope = this
        every { context.cs } returns scope
        val collector = mockk<CoderProviderLogCollector>()
        val archive = Path.of("logs.zip")
        coEvery { collector.collect(any()) } returns archive
        val page = CoderDiagnosticCollectorPage(context, collector)

        page.beforeShow()
        page.beforeShow()
        advanceUntilIdle()
        page.beforeShow()
        advanceUntilIdle()

        coVerify(exactly = 1) { collector.collect(any()) }
        verify(exactly = 1) { context.desktop.openPath(archive) }
        assertTrue(page.isFinished)
        assertFalse(page.isBusy.value)
        assertFalse(page.isCancellable.value)
        assertTrue(page.actionButtons.value.isNotEmpty())
    }

    @Test
    fun `cancel stops collection without revealing a partial archive`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val scope = this
        every { context.cs } returns scope
        val collector = mockk<CoderProviderLogCollector>()
        var stopped = false
        coEvery { collector.collect(any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val page = CoderDiagnosticCollectorPage(context, collector)

        page.beforeShow()
        runCurrent()
        assertTrue(page.isBusy.value)
        assertTrue(page.isCancellable.value)
        page.cancel()
        advanceUntilIdle()

        assertTrue(stopped)
        assertTrue(page.isFinished)
        assertFalse(page.isBusy.value)
        verify(exactly = 0) { context.desktop.openPath(any()) }
        verify(exactly = 0) { context.logger.warn(any<Throwable>(), any<String>()) }
    }

    @Test
    fun `closing the provider before the page is shown prevents collection`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val collector = mockk<CoderProviderLogCollector>()
        val page = CoderDiagnosticCollectorPage(context, collector)
        page.cancel()
        page.beforeShow()
        advanceUntilIdle()
        coVerify(exactly = 0) { collector.collect(any()) }
    }

    @Test
    fun `archive failure releases the busy page without opening a file`() = runTest {
        val context = mockk<CoderToolboxContext>(relaxed = true)
        val scope = this
        every { context.cs } returns scope
        val collector = mockk<CoderProviderLogCollector>()
        coEvery { collector.collect(any()) } throws IllegalStateException("disk unavailable")
        val page = CoderDiagnosticCollectorPage(context, collector)
        page.beforeShow()
        advanceUntilIdle()
        assertTrue(page.isFinished)
        assertFalse(page.isBusy.value)
        assertFalse(page.isCancellable.value)
        verify(exactly = 0) { context.desktop.openPath(any()) }
    }
}
