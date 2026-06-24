package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.util.withPath
import com.coder.toolbox.views.state.WizardModel
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.FieldModifier
import com.jetbrains.toolbox.api.ui.components.LinkField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
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
    private val model: WizardModel,
) : WizardStep {
    private val tokenField = TextField(context.i18n.ptrl("Token"), "", TextType.Password)
    private val linkField = LinkField(context.i18n.ptrl("Get a token"), "")

    override val panel: RowGroup = RowGroup(
        RowGroup.RowField(tokenField),
        RowGroup.RowField(linkField),
    )

    override fun onVisible() {
        resetError()
        model.url?.let { url ->
            tokenField.contentState.update {
                context.secrets.apiTokenFor(url) ?: ""
            }
            (linkField.urlState as MutableStateFlow).update {
                url.withPath("/login?redirect=%2Fcli-auth").toString()
            }
        } ?: run {
            reportError(context.i18n.pnotr("URL not configured in the previous step. Please go back and provide a proper URL."))
            (linkField.urlState as MutableStateFlow).update {
                ""
            }
            return
        }
    }

    override suspend fun onNext(): Boolean {
        val token = tokenField.contentState.value
        if (token.isBlank()) {
            reportError(context.i18n.ptrl("Token is required"))
            return false
        }

        model.token = token
        model.goToNext()
        return true
    }

    override fun onBack() {
        model.goToPrevious()
    }

    private fun reportError(message: LocalizableString?) {
        if (message != null) {
            context.logger.info(message.toString())
            tokenField.modifiers.value = listOf(FieldModifier.LocalizableError(message))
        } else {
            resetError()
        }
    }

    private fun resetError() {
        tokenField.modifiers.value = emptyList()
    }
}
