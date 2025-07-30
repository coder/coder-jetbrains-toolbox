package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.SignatureFallbackStrategy
import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.validateStrictWebUrl
import com.coder.toolbox.views.state.CoderCliSetupContext
import com.coder.toolbox.views.state.CoderCliSetupWizardState
import com.jetbrains.toolbox.api.ui.components.CheckboxField
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.LabelStyleType
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.flow.update
import java.net.MalformedURLException
import java.net.URL

/**
 * A page with a field for providing the Coder deployment URL.
 *
 * Populates with the provided URL, at which point the user can accept or
 * enter their own.
 */
class DeploymentUrlStep(
    private val context: CoderToolboxContext,
    private val notify: (String, Throwable) -> Unit
) :
    WizardStep {
    private val urlField = TextField(context.i18n.ptrl("Deployment URL"), "", TextType.General)
    private val emptyLine = LabelField(context.i18n.pnotr(""), LabelStyleType.Normal)

    private val signatureFallbackStrategyField = CheckboxField(
        context.settingsStore.fallbackOnCoderForSignatures.isAllowed(),
        context.i18n.ptrl("Verify binary signature using releases.coder.com when CLI signatures are not available from the deployment")
    )

    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    override val panel: RowGroup
        get() {
            if (context.settingsStore.fallbackOnCoderForSignatures == SignatureFallbackStrategy.NOT_CONFIGURED) {
                return RowGroup(
                    RowGroup.RowField(urlField),
                    RowGroup.RowField(emptyLine),
                    RowGroup.RowField(signatureFallbackStrategyField),
                    RowGroup.RowField(errorField)
                )

            }
            return RowGroup(
                RowGroup.RowField(urlField),
                RowGroup.RowField(errorField)
            )
        }

    override fun onVisible() {
        errorField.textState.update {
            context.i18n.pnotr("")
        }
        urlField.textState.update {
            context.secrets.lastDeploymentURL
        }

        signatureFallbackStrategyField.checkedState.update {
            context.settingsStore.fallbackOnCoderForSignatures.isAllowed()
        }
    }

    override fun onNext(): Boolean {
        context.settingsStore.updateSignatureFallbackStrategy(signatureFallbackStrategyField.checkedState.value)
        val url = urlField.textState.value
        if (url.isBlank()) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return false
        }
        try {
            CoderCliSetupContext.url = validateRawUrl(url)
        } catch (e: MalformedURLException) {
            notify("URL is invalid", e)
            return false
        }
        if (context.settingsStore.requireTokenAuth) {
            CoderCliSetupWizardState.goToNextStep()
        } else {
            CoderCliSetupWizardState.goToLastStep()
        }
        return true
    }

    /**
     * Throws [MalformedURLException] if the given string violates RFC-2396
     */
    private fun validateRawUrl(url: String): URL {
        try {
            val result = url.validateStrictWebUrl()
            if (result is Invalid) {
                throw MalformedURLException(result.reason)
            }
            return url.toURL()
        } catch (e: Exception) {
            throw MalformedURLException(e.message)
        }
    }

    override fun onBack() {
        // it's the first step. Can't go anywhere back from here
    }
}
