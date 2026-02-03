package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.HttpLoggingVerbosity.BASIC
import com.coder.toolbox.settings.HttpLoggingVerbosity.BODY
import com.coder.toolbox.settings.HttpLoggingVerbosity.HEADERS
import com.coder.toolbox.settings.HttpLoggingVerbosity.NONE
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.CheckboxField
import com.jetbrains.toolbox.api.ui.components.ComboBoxField
import com.jetbrains.toolbox.api.ui.components.ComboBoxField.LabelledValue
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A page for modifying Coder settings.
 *
 * TODO@JB: Even without an icon there is an unnecessary gap at the top.
 * TODO@JB: There is no scroll, and our settings do not fit.  As a consequence,
 *          I have not been able to test this page.
 */
class CoderSettingsPage(
    private val context: CoderToolboxContext,
    triggerSshConfig: Channel<Boolean>,
    private val onSettingsClosed: () -> Unit
) :
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
    private val useAppNameField =
        CheckboxField(settings.useAppNameAsTitle, context.i18n.ptrl("Use app name as main page title instead of URL"))

    private val disableSignatureVerificationField = CheckboxField(
        settings.disableSignatureVerification,
        context.i18n.ptrl("Disable Coder CLI signature verification")
    )
    private val signatureFallbackStrategyField =
        CheckboxField(
            settings.fallbackOnCoderForSignatures.isAllowed(),
            context.i18n.ptrl("Verify binary signature using releases.coder.com when CLI signatures are not available from the deployment")
        )

    private val httpLoggingField = ComboBoxField(
        ComboBoxField.Label(context.i18n.ptrl("HTTP logging level:")),
        settings.httpClientLogLevel,
        listOf(
            LabelledValue(context.i18n.ptrl("None"), NONE, listOf("" to "No logs")),
            LabelledValue(context.i18n.ptrl("Basic"), BASIC, listOf("" to "Method, URL and status")),
            LabelledValue(context.i18n.ptrl("Header"), HEADERS, listOf("" to " Basic + sanitized headers")),
            LabelledValue(context.i18n.ptrl("Body"), BODY, listOf("" to "Headers + body content")),
        )
    )

    private val enableBinaryDirectoryFallbackField = CheckboxField(
        settings.enableBinaryDirectoryFallback,
        context.i18n.ptrl("Enable binary directory fallback")
    )
    private val headerCommandField = TextField(
        context.i18n.ptrl("Header command"),
        settings.headerCommand ?: "",
        TextType.General
    )

    private val tlsCertPathField = TextField(
        context.i18n.ptrl("TLS cert path"),
        settings.tls.certPath ?: "",
        TextType.General
    )
    private val tlsKeyPathField = TextField(
        context.i18n.ptrl("TLS key path"),
        settings.tls.keyPath ?: "", TextType.General
    )
    private val tlsCAPathField =
        TextField(context.i18n.ptrl("TLS CA path"), settings.tls.caPath ?: "", TextType.General)
    private val tlsAlternateHostnameField =
        TextField(context.i18n.ptrl("TLS alternate hostname"), settings.tls.altHostname ?: "", TextType.General)

    private val disableAutostartField = CheckboxField(
        settings.disableAutostart,
        context.i18n.ptrl("Disable autostart")
    )

    private val enableSshWildCardConfig = CheckboxField(
        settings.isSshWildcardConfigEnabled,
        context.i18n.ptrl("Enable SSH wildcard config")
    )

    private val sshConnectionTimeoutField = TextField(
        context.i18n.ptrl("SSH connection timeout (seconds)"),
        settings.sshConnectionTimeoutInSeconds.toString(),
        TextType.Integer
    )

    private val sshExtraArgs = TextField(
        context.i18n.ptrl("Extra SSH options"),
        settings.sshConfigOptions ?: "",
        TextType.General
    )

    private val sshLogDirField = TextField(
        context.i18n.ptrl("SSH proxy log directory"),
        settings.sshLogDirectory ?: "",
        TextType.General
    )
    private val networkInfoDirField = TextField(
        context.i18n.ptrl("SSH network metrics directory"),
        settings.networkInfoDir,
        TextType.General
    )

    private lateinit var visibilityUpdateJob: Job
    override val fields: StateFlow<List<UiField>> = MutableStateFlow(
        listOf(
            binarySourceField,
            enableDownloadsField,
            useAppNameField,
            binaryDirectoryField,
            enableBinaryDirectoryFallbackField,
            disableSignatureVerificationField,
            signatureFallbackStrategyField,
            httpLoggingField,
            dataDirectoryField,
            headerCommandField,
            tlsCertPathField,
            tlsKeyPathField,
            tlsCAPathField,
            tlsAlternateHostnameField,
            disableAutostartField,
            enableSshWildCardConfig,
            sshConnectionTimeoutField,
            sshLogDirField,
            networkInfoDirField,
            sshExtraArgs,
        )
    )

    override val actionButtons: StateFlow<List<RunnableActionDescription>> = MutableStateFlow(
        listOf(
            Action(context, "Save", closesPage = true) {
                with(context.settingsStore) {
                    updateBinarySource(binarySourceField.contentState.value)
                    updateBinaryDirectory(binaryDirectoryField.contentState.value)
                    updateDataDirectory(dataDirectoryField.contentState.value)
                    updateEnableDownloads(enableDownloadsField.checkedState.value)
                    updateUseAppNameAsTitle(useAppNameField.checkedState.value)
                    updateDisableSignatureVerification(disableSignatureVerificationField.checkedState.value)
                    updateSignatureFallbackStrategy(signatureFallbackStrategyField.checkedState.value)
                    updateHttpClientLogLevel(httpLoggingField.selectedValueState.value)
                    updateBinaryDirectoryFallback(enableBinaryDirectoryFallbackField.checkedState.value)
                    updateHeaderCommand(headerCommandField.contentState.value)
                    updateCertPath(tlsCertPathField.contentState.value)
                    updateKeyPath(tlsKeyPathField.contentState.value)
                    updateCAPath(tlsCAPathField.contentState.value)
                    updateAltHostname(tlsAlternateHostnameField.contentState.value)
                    updateDisableAutostart(disableAutostartField.checkedState.value)

                    val sshWildcardEnabled = enableSshWildCardConfig.checkedState.value
                    val sshTimeout = sshConnectionTimeoutField.contentState.value.toInt()

                    val sshSettingsChanged = sshWildcardEnabled != settings.isSshWildcardConfigEnabled ||
                            sshTimeout != settings.sshConnectionTimeoutInSeconds

                    updateEnableSshWildcardConfig(sshWildcardEnabled)
                    updateSshConnectionTimeoutInSeconds(sshTimeout)

                    if (sshSettingsChanged) {
                        runCatching {
                            triggerSshConfig.send(true)
                            context.logger.info("Settings have been modified, ssh config is going to be regenerated...")
                        }
                    }

                    updateSshLogDir(sshLogDirField.contentState.value)
                    updateNetworkInfoDir(networkInfoDirField.contentState.value)
                    updateSshConfigOptions(sshExtraArgs.contentState.value)
                }
            }
        )
    )

    override fun beforeShow() {
        // update the value of all fields
        binarySourceField.contentState.update {
            settings.binarySource ?: ""
        }
        binaryDirectoryField.contentState.update {
            settings.binaryDirectory ?: ""
        }
        dataDirectoryField.contentState.update {
            settings.dataDirectory ?: ""
        }
        enableDownloadsField.checkedState.update {
            settings.enableDownloads
        }
        useAppNameField.checkedState.update {
            settings.useAppNameAsTitle
        }
        signatureFallbackStrategyField.checkedState.update {
            settings.fallbackOnCoderForSignatures.isAllowed()
        }

        enableBinaryDirectoryFallbackField.checkedState.update {
            settings.enableBinaryDirectoryFallback
        }

        headerCommandField.contentState.update {
            settings.headerCommand ?: ""
        }

        tlsCertPathField.contentState.update {
            settings.tls.certPath ?: ""
        }

        tlsKeyPathField.contentState.update {
            settings.tls.keyPath ?: ""
        }

        tlsCAPathField.contentState.update {
            settings.tls.caPath ?: ""
        }

        tlsAlternateHostnameField.contentState.update {
            settings.tls.altHostname ?: ""
        }

        disableAutostartField.checkedState.update {
            settings.disableAutostart
        }

        enableSshWildCardConfig.checkedState.update {
            settings.isSshWildcardConfigEnabled
        }

        sshConnectionTimeoutField.contentState.update {
            settings.sshConnectionTimeoutInSeconds.toString()
        }

        sshExtraArgs.contentState.update {
            settings.sshConfigOptions ?: ""
        }

        sshLogDirField.contentState.update {
            settings.sshLogDirectory ?: ""
        }

        networkInfoDirField.contentState.update {
            settings.networkInfoDir
        }

        visibilityUpdateJob = context.cs.launch(CoroutineName("Signature Verification Fallback Setting")) {
            disableSignatureVerificationField.checkedState.collect { state ->
                signatureFallbackStrategyField.visibility.update {
                    // the fallback checkbox should not be visible
                    // if signature verification is disabled
                    !state
                }
            }
        }
    }

    override fun afterHide() {
        visibilityUpdateJob.cancel()
        onSettingsClosed()
    }
}
