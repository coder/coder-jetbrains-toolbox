package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.CheckboxField
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A page for modifying Coder settings.
 *
 * TODO@JB: Even without an icon there is an unnecessary gap at the top.
 * TODO@JB: There is no scroll, and our settings do not fit.  As a consequence,
 *          I have not been able to test this page.
 */
class CoderSettingsPage(context: CoderToolboxContext) : CoderPage(context, context.i18n.ptrl("Coder Settings"), false) {
    // TODO: Copy over the descriptions, holding until I can test this page.
    private val binarySourceField =
        TextField(context.i18n.ptrl("Binary source"), context.settings.binarySource, TextType.General)
    private val binaryDirectoryField =
        TextField(context.i18n.ptrl("Binary directory"), context.settings.binaryDirectory, TextType.General)
    private val dataDirectoryField =
        TextField(context.i18n.ptrl("Data directory"), context.settings.dataDirectory, TextType.General)
    private val enableDownloadsField =
        CheckboxField(context.settings.enableDownloads, context.i18n.ptrl("Enable downloads"))
    private val enableBinaryDirectoryFallbackField =
        CheckboxField(
            context.settings.enableBinaryDirectoryFallback,
            context.i18n.ptrl("Enable binary directory fallback")
        )
    private val headerCommandField =
        TextField(context.i18n.ptrl("Header command"), context.settings.headerCommand, TextType.General)
    private val tlsCertPathField =
        TextField(context.i18n.ptrl("TLS cert path"), context.settings.tlsCertPath, TextType.General)
    private val tlsKeyPathField =
        TextField(context.i18n.ptrl("TLS key path"), context.settings.tlsKeyPath, TextType.General)
    private val tlsCAPathField =
        TextField(context.i18n.ptrl("TLS CA path"), context.settings.tlsCAPath, TextType.General)
    private val tlsAlternateHostnameField =
        TextField(context.i18n.ptrl("TLS alternate hostname"), context.settings.tlsAlternateHostname, TextType.General)
    private val disableAutostartField =
        CheckboxField(context.settings.disableAutostart, context.i18n.ptrl("Disable autostart"))

    override val fields: StateFlow<List<UiField>> = MutableStateFlow(
        listOf(
            binarySourceField,
            enableDownloadsField,
            binaryDirectoryField,
            enableBinaryDirectoryFallbackField,
            dataDirectoryField,
            headerCommandField,
            tlsCertPathField,
            tlsKeyPathField,
            tlsCAPathField,
            tlsAlternateHostnameField,
            disableAutostartField
        )
    )

    override val actionButtons: StateFlow<List<RunnableActionDescription>> = MutableStateFlow(
        listOf(
            Action(context.i18n.ptrl("Save"), closesPage = true) {
                context.settings.binarySource = binarySourceField.textState.value
                context.settings.binaryDirectory = binaryDirectoryField.textState.value
                context.settings.dataDirectory = dataDirectoryField.textState.value
                context.settings.enableDownloads = enableDownloadsField.checkedState.value
                context.settings.enableBinaryDirectoryFallback = enableBinaryDirectoryFallbackField.checkedState.value
                context.settings.headerCommand = headerCommandField.textState.value
                context.settings.tlsCertPath = tlsCertPathField.textState.value
                context.settings.tlsKeyPath = tlsKeyPathField.textState.value
                context.settings.tlsCAPath = tlsCAPathField.textState.value
                context.settings.tlsAlternateHostname = tlsAlternateHostnameField.textState.value
                context.settings.disableAutostart = disableAutostartField.checkedState.value
            },
        )
    )
}
