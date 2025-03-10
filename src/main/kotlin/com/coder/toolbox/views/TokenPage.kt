package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.Source
import com.coder.toolbox.util.withPath
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.LinkField
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URL

/**
 * A page with a field for providing the token.
 *
 * Populate with the provided token, at which point the user can accept or
 * enter their own.
 */
class TokenPage(
    private val context: CoderToolboxContext,
    deploymentURL: URL,
    token: Pair<String, Source>?,
    private val onToken: ((token: String) -> Unit),
) : CoderPage(context, context.i18n.ptrl("Enter your token")) {
    private val tokenField = TextField(context.i18n.ptrl("Token"), token?.first ?: "", TextType.Password)

    /**
     * Fields for this page, displayed in order.
     *
     * TODO@JB: Fields are reset when you navigate back.
     *          Ideally they remember what the user entered.
     */
    override val fields: StateFlow<List<UiField>> = MutableStateFlow(
        listOfNotNull(
            tokenField,
            LabelField(
                context.i18n.pnotr(
                    token?.second?.description("token")
                        ?: "No existing token for ${deploymentURL.host} found."
                ),
            ),
            // TODO@JB: The link text displays twice.
            LinkField(
                context.i18n.ptrl("Get a token"),
                deploymentURL.withPath("/login?redirect=%2Fcli-auth").toString()
            ),
            errorField,
        )
    )

    /**
     * Buttons displayed at the bottom of the page.
     */
    override val actionButtons: StateFlow<List<RunnableActionDescription>> = MutableStateFlow(
        listOf(
            Action(context.i18n.ptrl("Connect"), closesPage = false) { submit(tokenField.textState.value) },
        )
    )

    /**
     * Call onToken with the token, or error if blank.
     */
    private fun submit(token: String) {
        if (token.isBlank()) {
            updateError("Token is required")
        } else {
            updateError(null)
            onToken(token)
        }
    }
}
