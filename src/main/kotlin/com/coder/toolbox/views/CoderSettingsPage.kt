package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.services.CoderSettingsService
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
class CoderSettingsPage(
    context: CoderToolboxContext,
    private val settings: CoderSettingsService,
) : CoderPage(context, context.i18n.ptrl("Coder Settings"), false) {
    // TODO: Copy over the descriptions, holding until I can test this page.
    private val binarySourceField =
        TextField(context.i18n.ptrl("Binary source"), settings.binarySource, TextType.General)
    private val binaryDirectoryField =
        TextField(context.i18n.ptrl("Binary directory"), settings.binaryDirectory, TextType.General)
    private val dataDirectoryField =
        TextField(context.i18n.ptrl("Data directory"), settings.dataDirectory, TextType.General)
    private val enableDownloadsField = CheckboxField(settings.enableDownloads, context.i18n.ptrl("Enable downloads"))
    private val enableBinaryDirectoryFallbackField =
        CheckboxField(settings.enableBinaryDirectoryFallback, context.i18n.ptrl("Enable binary directory fallback"))
    private val headerCommandField =
        TextField(context.i18n.ptrl("Header command"), settings.headerCommand, TextType.General)
    private val tlsCertPathField = TextField(context.i18n.ptrl("TLS cert path"), settings.tlsCertPath, TextType.General)
    private val tlsKeyPathField = TextField(context.i18n.ptrl("TLS key path"), settings.tlsKeyPath, TextType.General)
    private val tlsCAPathField = TextField(context.i18n.ptrl("TLS CA path"), settings.tlsCAPath, TextType.General)
    private val tlsAlternateHostnameField =
        TextField(context.i18n.ptrl("TLS alternate hostname"), settings.tlsAlternateHostname, TextType.General)
    private val disableAutostartField = CheckboxField(settings.disableAutostart, context.i18n.ptrl("Disable autostart"))

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
                settings.binarySource = binarySourceField.textState.value
                settings.binaryDirectory = binaryDirectoryField.textState.value
                settings.dataDirectory = dataDirectoryField.textState.value
                settings.enableDownloads = enableDownloadsField.checkedState.value
                settings.enableBinaryDirectoryFallback = enableBinaryDirectoryFallbackField.checkedState.value
                settings.headerCommand = headerCommandField.textState.value
                settings.tlsCertPath = tlsCertPathField.textState.value
                settings.tlsKeyPath = tlsKeyPathField.textState.value
                settings.tlsCAPath = tlsCAPathField.textState.value
                settings.tlsAlternateHostname = tlsAlternateHostnameField.textState.value
                settings.disableAutostart = disableAutostartField.checkedState.value
            },
        )
    )
}
