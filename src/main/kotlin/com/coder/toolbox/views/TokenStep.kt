package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.SettingSource
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.state.AuthWizardState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.LabelField
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
class TokenStep(private val context: CoderToolboxContext) : WizardStep {
    private val tokenField = TextField(context.i18n.ptrl("Token"), "", TextType.General)
    private val descriptionField = LabelField(context.i18n.pnotr(""))
    private val linkField = LinkField(context.i18n.ptrl("Get a token"), "")
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(tokenField),
        RowGroup.RowField(descriptionField),
        RowGroup.RowField(linkField),
        RowGroup.RowField(errorField)
    )
    override val nextButtonTitle: LocalizableString? = context.i18n.ptrl("Connect")
    override val closesWizard: Boolean = false

    override fun onVisible() {
        tokenField.textState.update {
            getToken(context.deploymentUrl?.first)?.first ?: ""
        }
        descriptionField.textState.update {
            context.i18n.pnotr(
                getToken(context.deploymentUrl?.first)?.second?.description("token")
                    ?: "No existing token for ${context.deploymentUrl} found."
            )
        }
        (linkField.urlState as MutableStateFlow).update {
            context.deploymentUrl?.first?.toURL()?.withPath("/login?redirect=%2Fcli-auth")?.toString() ?: ""
        }
    }

    override fun onNext(): Boolean {
        val token = tokenField.textState.value
        if (token.isBlank()) {
            errorField.textState.update { context.i18n.ptrl("Token is required") }
            return false
        }

        context.secrets.lastToken = token
        AuthWizardState.goToNextStep()
        return true
    }

    override fun onBack() {
        AuthWizardState.goToPreviousStep()
    }

    /**
     * Try to find a token.
     *
     * Order of preference:
     *
     * 1. Last used token, if it was for this deployment.
     * 2. Token on disk for this deployment.
     * 3. Global token for Coder, if it matches the deployment.
     */
    private fun getToken(deploymentURL: String?): Pair<String, SettingSource>? = context.secrets.lastToken.let {
        if (it.isNotBlank() && context.secrets.lastDeploymentURL == deploymentURL) {
            it to SettingSource.LAST_USED
        } else {
            if (deploymentURL != null) {
                context.settingsStore.token(deploymentURL.toURL())
            } else null
        }
    }
}
