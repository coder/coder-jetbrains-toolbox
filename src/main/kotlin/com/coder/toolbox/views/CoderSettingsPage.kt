package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.CheckboxField
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A page for modifying Coder settings.
 *
 * TODO@JB: Even without an icon there is an unnecessary gap at the top.
 * TODO@JB: There is no scroll, and our settings do not fit.  As a consequence,
 *          I have not been able to test this page.
 */
class CoderSettingsPage(context: CoderToolboxContext, triggerSshConfig: Channel<Boolean>) :
    CoderPage(MutableStateFlow(context.i18n.ptrl("Coder Settings")), false) {
    private val settings = context.settingsStore.readOnly()

    // TODO: Copy over the descriptions, holding until I can test this page.
    private val binarySourceField =
        TextField(context.i18n.ptrl("Binary source"), settings.binarySource ?: "", TextType.General)
    private val binaryDirectoryField =
        TextField(context.i18n.ptrl("Binary directory"), settings.binaryDirectory ?: "", TextType.General)
    private val dataDirectoryField =
        TextField(context.i18n.ptrl("Data directory"), settings.dataDirectory ?: "", TextType.General)
    private val enableDownloadsField =
        CheckboxField(settings.enableDownloads, context.i18n.ptrl("Enable downloads"))
    private val enableBinaryDirectoryFallbackField =
        CheckboxField(
            settings.enableBinaryDirectoryFallback,
            context.i18n.ptrl("Enable binary directory fallback")
        )
    private val headerCommandField =
        TextField(context.i18n.ptrl("Header command"), settings.headerCommand ?: "", TextType.General)
    private val tlsCertPathField =
        TextField(context.i18n.ptrl("TLS cert path"), settings.tls.certPath ?: "", TextType.General)
    private val tlsKeyPathField =
        TextField(context.i18n.ptrl("TLS key path"), settings.tls.keyPath ?: "", TextType.General)
    private val tlsCAPathField =
        TextField(context.i18n.ptrl("TLS CA path"), settings.tls.caPath ?: "", TextType.General)
    private val tlsAlternateHostnameField =
        TextField(context.i18n.ptrl("TLS alternate hostname"), settings.tls.altHostname ?: "", TextType.General)
    private val disableAutostartField =
        CheckboxField(settings.disableAutostart, context.i18n.ptrl("Disable autostart"))

    private val enableSshWildCardConfig =
        CheckboxField(settings.isSshWildcardConfigEnabled, context.i18n.ptrl("Enable SSH wildcard config"))
    private val sshExtraArgs =
        TextField(context.i18n.ptrl("Extra SSH options"), settings.sshConfigOptions ?: "", TextType.General)
    private val sshLogDirField =
        TextField(context.i18n.ptrl("SSH proxy log directory"), settings.sshLogDirectory ?: "", TextType.General)
    private val networkInfoDirField =
        TextField(context.i18n.ptrl("SSH network metrics directory"), settings.networkInfoDir, TextType.General)


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
            disableAutostartField,
            enableSshWildCardConfig,
            sshLogDirField,
            networkInfoDirField,
            sshExtraArgs,
        )
    )

    override val actionButtons: StateFlow<List<RunnableActionDescription>> = MutableStateFlow(
        listOf(
            Action(context.i18n.ptrl("Save"), closesPage = true) {
                context.settingsStore.updateBinarySource(binarySourceField.textState.value)
                context.settingsStore.updateBinaryDirectory(binaryDirectoryField.textState.value)
                context.settingsStore.updateDataDirectory(dataDirectoryField.textState.value)
                context.settingsStore.updateEnableDownloads(enableDownloadsField.checkedState.value)
                context.settingsStore.updateBinaryDirectoryFallback(enableBinaryDirectoryFallbackField.checkedState.value)
                context.settingsStore.updateHeaderCommand(headerCommandField.textState.value)
                context.settingsStore.updateCertPath(tlsCertPathField.textState.value)
                context.settingsStore.updateKeyPath(tlsKeyPathField.textState.value)
                context.settingsStore.updateCAPath(tlsCAPathField.textState.value)
                context.settingsStore.updateAltHostname(tlsAlternateHostnameField.textState.value)
                context.settingsStore.updateDisableAutostart(disableAutostartField.checkedState.value)
                val oldIsSshWildcardConfigEnabled = settings.isSshWildcardConfigEnabled
                context.settingsStore.updateEnableSshWildcardConfig(enableSshWildCardConfig.checkedState.value)

                if (enableSshWildCardConfig.checkedState.value != oldIsSshWildcardConfigEnabled) {
                    context.cs.launch {
                        try {
                            triggerSshConfig.send(true)
                            context.logger.info("Wildcard settings have been modified from $oldIsSshWildcardConfigEnabled to ${!oldIsSshWildcardConfigEnabled}, ssh config is going to be regenerated...")
                        } catch (_: ClosedSendChannelException) {
                        }
                    }
                }
                context.settingsStore.updateSshLogDir(sshLogDirField.textState.value)
                context.settingsStore.updateNetworkInfoDir(networkInfoDirField.textState.value)
                context.settingsStore.updateSshConfigOptions(sshExtraArgs.textState.value)
            }
        )
    )
}
