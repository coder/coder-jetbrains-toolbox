package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.SettingSource
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URL

/**
 * A page with a field for providing the Coder deployment URL.
 *
 * Populates with the provided URL, at which point the user can accept or
 * enter their own.
 */
class SignInPage(
    private val context: CoderToolboxContext,
    private val deploymentURL: Pair<String, SettingSource>?,
    private val onSignIn: (deploymentURL: URL) -> Unit,
) : CoderPage(context, context.i18n.ptrl("Sign In to Coder")) {
    private val urlField = TextField(context.i18n.ptrl("Deployment URL"), deploymentURL?.first ?: "", TextType.General)

    /**
     * Fields for this page, displayed in order.
     *
     * TODO@JB: Fields are reset when you navigate back.
     *          Ideally they remember what the user entered.
     */
    override val fields: StateFlow<List<UiField>> = MutableStateFlow(
        listOfNotNull(
            urlField,
            deploymentURL?.let { LabelField(context.i18n.pnotr(deploymentURL.second.description("URL"))) },
            errorField,
        )
    )

    /**
     * Buttons displayed at the bottom of the page.
     */
    override val actionButtons: StateFlow<List<RunnableActionDescription>> = MutableStateFlow(
        listOf(
            Action(context.i18n.ptrl("Sign In"), closesPage = false) { submit() },
        )
    )

    /**
     * Call onSignIn with the URL, or error if blank.
     */
    private fun submit() {
        val urlRaw = urlField.textState.value
        // Ensure the URL can be parsed.
        try {
            if (urlRaw.isBlank()) {
                throw Exception("URL is required")
            }
            // Prefix the protocol if the user left it out.
            // URL() will throw if the URL is invalid.
            onSignIn(
                URL(
                    if (!urlRaw.startsWith("http://") && !urlRaw.startsWith("https://")) {
                        "https://$urlRaw"
                    } else {
                        urlRaw
                    },
                ),
            )
        } catch (ex: Exception) {
            // TODO@JB: Works on the other page, but not this one.
            updateError(ex.message)
        }
    }
}
