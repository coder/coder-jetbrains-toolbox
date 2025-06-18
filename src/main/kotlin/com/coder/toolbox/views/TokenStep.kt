package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.state.CoderCliSetupContext
import com.coder.toolbox.views.state.CoderCliSetupWizardState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.LinkField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A page with a field for providing the token.
 *
 * Populate with the provided token, at which point the user can accept or
 * enter their own.
 */
class TokenStep(
    private val context: CoderToolboxContext,
) : WizardStep {
    private val tokenField = TextField(context.i18n.ptrl("Token"), "", TextType.Password)
    private val linkField = LinkField(context.i18n.ptrl("Get a token"), "")
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(tokenField),
        RowGroup.RowField(linkField),
        RowGroup.RowField(errorField)
    )
    override val nextButtonTitle: LocalizableString? = context.i18n.ptrl("Connect")

    override fun onVisible() {
        errorField.textState.update {
            context.i18n.pnotr("")
        }
        if (CoderCliSetupContext.hasUrl()) {
            tokenField.textState.update {
                context.secrets.tokenFor(CoderCliSetupContext.url!!) ?: ""
            }
        } else {
            errorField.textState.update {
                context.i18n.pnotr("URL not configure in the previous step. Please go back and provide a proper URL.")
                return
            }
        }
        (linkField.urlState as MutableStateFlow).update {
            CoderCliSetupContext.url!!.withPath("/login?redirect=%2Fcli-auth")?.toString() ?: ""
        }
    }

    override fun onNext(): Boolean {
        val token = tokenField.textState.value
        if (token.isBlank()) {
            errorField.textState.update { context.i18n.ptrl("Token is required") }
            return false
        }

        CoderCliSetupContext.token = token
        CoderCliSetupWizardState.goToNextStep()
        return true
    }

    override fun onBack() {
        CoderCliSetupWizardState.goToPreviousStep()
    }
}
