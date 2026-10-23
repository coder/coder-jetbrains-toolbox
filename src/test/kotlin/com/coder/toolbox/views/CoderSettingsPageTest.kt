package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.pluginTestSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.FieldModifier
import com.jetbrains.toolbox.api.ui.components.SectionField
import com.jetbrains.toolbox.api.ui.components.TextField
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderSettingsPageTest {
    @TempDir
    lateinit var tempDir: Path

    private val settings = CoderSettingsStore(
        pluginTestSettingsStore(), Environment(), mockk<Logger>(relaxed = true)
    )
    private val context = mockk<CoderToolboxContext>(relaxed = true)
    private val bufferLabel = mockk<LocalizableString>()

    init {
        every { context.settingsStore } returns settings
        every { context.i18n.ptrl("Connection log buffer size") } returns bufferLabel
    }

    @Test
    fun `connection log buffer field defaults to 1000 and accepts the inclusive range`() {
        val page = createPage()
        val field = bufferField(page)
        assertEquals("1000", field.contentState.value)

        listOf("0", "1", "2500", "10000").forEach { value ->
            field.contentState.value = value
            assertTrue(page.actionButtons.value.single().validate(), value)
        }
    }

    @Test
    fun `save validation rejects invalid connection log buffer input`() {
        val page = createPage()
        val field = bufferField(page)

        listOf("", "invalid", "1.5", "-1", "10001", "2147483648").forEach { value ->
            field.contentState.value = value
            assertFalse(page.actionButtons.value.single().validate(), value)
            assertTrue(field.modifiers.value.any { it is FieldModifier.LocalizableError })
            assertEquals(1000, settings.connectionLogBufferSize)
        }

        field.contentState.value = "1000"
        assertTrue(page.actionButtons.value.single().validate())
        assertTrue(field.modifiers.value.none { it is FieldModifier.LocalizableError })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `save persists the buffer size and reopening reloads the current value`() = runTest {
        val testScope = this
        every { context.cs } returns testScope
        val page = createPage()
        val field = bufferField(page)
        val save = page.actionButtons.value.single()

        listOf("0", "10000").forEach { value ->
            field.contentState.value = value
            assertTrue(save.validate())
            save.run()
            advanceUntilIdle()
            assertEquals(value.toInt(), settings.readOnly().connectionLogBufferSize)
        }

        settings.updateConnectionLogBufferSize(2500)
        page.beforeShow()
        try {
            assertEquals("2500", field.contentState.value)
        } finally {
            page.afterHide()
            advanceUntilIdle()
        }
    }

    private fun createPage(): CoderSettingsPage {
        settings.updateSshConfigPath(tempDir.resolve("config").toString())
        return CoderSettingsPage(context, Channel(Channel.CONFLATED)) {}
    }

    private fun bufferField(page: CoderSettingsPage): TextField = page.fields.value
        .filterIsInstance<SectionField>()
        .flatMap { it.contentState.value }
        .filterIsInstance<TextField>()
        .single { it.labelState.value === bufferLabel }
}
