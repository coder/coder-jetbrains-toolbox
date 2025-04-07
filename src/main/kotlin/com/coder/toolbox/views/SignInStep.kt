package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.views.state.AuthWizardState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.flow.update

/**
 * A page with a field for providing the Coder deployment URL.
 *
 * Populates with the provided URL, at which point the user can accept or
 * enter their own.
 */
class SignInStep(private val context: CoderToolboxContext) : WizardStep {
    private val urlField = TextField(context.i18n.ptrl("Deployment URL"), "", TextType.General)
    private val descriptionField = LabelField(context.i18n.pnotr(""))
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(urlField),
        RowGroup.RowField(descriptionField),
        RowGroup.RowField(errorField)
    )

    override val nextButtonTitle: LocalizableString? = context.i18n.ptrl("Sign In")

    override val closesWizard: Boolean = false

    override fun onVisible() {
        urlField.textState.update {
            context.deploymentUrl?.first ?: ""
        }

        descriptionField.textState.update {
            context.i18n.pnotr(context.deploymentUrl?.second?.description("URL") ?: "")
        }
    }

    override fun onNext(): Boolean {
        var url = urlField.textState.value
        if (url.isBlank()) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return false
        }
        url = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        context.secrets.lastDeploymentURL = url
        AuthWizardState.goToNextStep()
        return true
    }

    override fun onBack() {
        // it's the first step. Can't go anywhere back from here
    }
}
