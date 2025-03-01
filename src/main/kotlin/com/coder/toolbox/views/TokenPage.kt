package com.coder.toolbox.views

import com.coder.toolbox.settings.Source
import com.coder.toolbox.util.withPath
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.LinkField
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import java.net.URL

/**
 * A page with a field for providing the token.
 *
 * Populate with the provided token, at which point the user can accept or
 * enter their own.
 */
class TokenPage(
    deploymentURL: URL,
    token: Pair<String, Source>?,
    private val onToken: ((token: String) -> Unit),
) : CoderPage("Enter your token") {
    private val tokenField = TextField("Token", token?.first ?: "", TextType.General)

    /**
     * Fields for this page, displayed in order.
     *
     * TODO@JB: Fields are reset when you navigate back.
     *          Ideally they remember what the user entered.
     */
    override val fields: MutableList<UiField> = listOfNotNull(
        tokenField,
        LabelField(
            token?.second?.description("token")
                ?: "No existing token for ${deploymentURL.host} found.",
        ),
        // TODO@JB: The link text displays twice.
        LinkField("Get a token", deploymentURL.withPath("/login?redirect=%2Fcli-auth").toString()),
        errorField,
    ).toMutableList()

    /**
     * Buttons displayed at the bottom of the page.
     */
    override val actionButtons: MutableList<RunnableActionDescription> = mutableListOf(
        Action("Connect", closesPage = false) { submit(tokenField.text.value) },
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
