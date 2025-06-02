package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.TextType

/**
 * Dialog implementation for standalone Gateway.
 *
 * This is meant to mimic ToolboxUi.
 */
class DialogUi(private val context: CoderToolboxContext) {

    suspend fun confirm(title: LocalizableString, description: LocalizableString): Boolean {
        return context.ui.showOkCancelPopup(title, description, context.i18n.ptrl("Yes"), context.i18n.ptrl("No"))
    }

    suspend fun ask(
        title: LocalizableString,
        description: LocalizableString,
        placeholder: LocalizableString? = null,
    ): String? {
        return context.ui.showTextInputPopup(
            title, description, placeholder, TextType.General, context.i18n.ptrl("OK"), context.i18n.ptrl("Cancel")
        )
    }
}
