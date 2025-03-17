package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.browser.BrowserUtil
import com.coder.toolbox.settings.CoderSettings
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.TextType
import java.net.URL

/**
 * Dialog implementation for standalone Gateway.
 *
 * This is meant to mimic ToolboxUi.
 */
class DialogUi(
    private val context: CoderToolboxContext,
    private val settings: CoderSettings,
) {

    suspend fun confirm(title: LocalizableString, description: LocalizableString): Boolean {
        return context.ui.showOkCancelPopup(title, description, context.i18n.ptrl("Yes"), context.i18n.ptrl("No"))
    }

    suspend fun ask(
        title: LocalizableString,
        description: LocalizableString,
        placeholder: LocalizableString? = null,
    ): String? {
        return context.ui.showTextInputPopup(
            title,
            description,
            placeholder,
            TextType.General,
            context.i18n.ptrl("OK"),
            context.i18n.ptrl("Cancel")
        )
    }

    suspend fun askPassword(
        title: LocalizableString,
        description: LocalizableString,
        placeholder: LocalizableString? = null,
    ): String? {
        return context.ui.showTextInputPopup(
            title,
            description,
            placeholder,
            TextType.Password,
            context.i18n.ptrl("OK"),
            context.i18n.ptrl("Cancel")
        )
    }

    private suspend fun openUrl(url: URL) {
        BrowserUtil.browse(url.toString()) {
            context.ui.showErrorInfoPopup(it)
        }
    }

    /**
     * Open a dialog for providing the token.
     */
    suspend fun askToken(
        url: URL,
    ): String? {
        openUrl(url.withPath("/login?redirect=%2Fcli-auth"))
        return askPassword(
            title = context.i18n.ptrl("Session Token"),
            description = context.i18n.pnotr("Please paste the session token from the web-page"),
            placeholder = context.i18n.pnotr("")
        )
    }
}
