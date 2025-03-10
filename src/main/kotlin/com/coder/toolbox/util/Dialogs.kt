package com.coder.toolbox.util

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.browser.BrowserUtil
import com.coder.toolbox.settings.CoderSettings
import com.coder.toolbox.settings.Source
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
        // TODO check: there is no link or error support in Toolbox so for now isError and  link are unused.
        isError: Boolean = false,
        link: Pair<String, String>? = null,
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

    private suspend fun openUrl(url: URL) {
        BrowserUtil.browse(url.toString()) {
            context.ui.showErrorInfoPopup(it)
        }
    }

    /**
     * Open a dialog for providing the token.  Show any existing token so
     * the user can validate it if a previous connection failed.
     *
     * If we have not already tried once (no error) and the user has not checked
     * the existing token box then also open a browser to the auth page.
     *
     * If the user has checked the existing token box then return the token
     * on disk immediately and skip the dialog (this will overwrite any
     * other existing token) unless this is a retry to avoid clobbering the
     * token that just failed.
     */
    suspend fun askToken(
        url: URL,
        token: Pair<String, Source>?,
        useExisting: Boolean,
        error: String?,
    ): Pair<String, Source>? {
        val getTokenUrl = url.withPath("/login?redirect=%2Fcli-auth")

        // On the first run (no error) either open a browser to generate a new
        // token or, if using an existing token, use the token on disk if it
        // exists otherwise assume the user already copied an existing token and
        // they will paste in.
        if (error == null) {
            if (!useExisting) {
                openUrl(getTokenUrl)
            } else {
                // Look on disk in case we already have a token, either in
                // the deployment's config or the global config.
                val tryToken = settings.token(url)
                if (tryToken != null && tryToken.first != token?.first) {
                    return tryToken
                }
            }
        }

        // On subsequent tries or if not using an existing token, ask the user
        // for the token.
        val tokenFromUser =
            ask(
                title = context.i18n.ptrl("Session Token"),
                description = context.i18n.pnotr(
                    error
                        ?: token?.second?.description("token")
                        ?: "No existing token for ${url.host} found."
                ),
                placeholder = token?.first?.let { context.i18n.pnotr(it) },
                link = Pair("Session Token:", getTokenUrl.toString()),
                isError = error != null,
            )
        if (tokenFromUser.isNullOrBlank()) {
            return null
        }
        // If the user submitted the same token, keep the same source too.
        val source = if (tokenFromUser == token?.first) token.second else Source.USER
        return Pair(tokenFromUser, source)
    }
}
