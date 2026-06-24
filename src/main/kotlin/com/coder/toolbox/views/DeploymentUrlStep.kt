package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.browser.browse
import com.coder.toolbox.oauth.ClientRegistrationRequest
import com.coder.toolbox.oauth.OAuth2Client
import com.coder.toolbox.oauth.PKCEGenerator
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.oauth.getPreferredOrAvailable
import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.validateStrictWebUrl
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.WizardModel
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.CheckboxField
import com.jetbrains.toolbox.api.ui.components.FieldModifier
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.LabelStyleType
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.MalformedURLException
import java.net.URL
import java.util.UUID

private const val REDIRECT_URI = "jetbrains://gateway/com.coder.toolbox/auth"
private const val OAUTH2_SCOPE: String =
    "coder:workspaces.operate coder:workspaces.delete coder:workspaces.access user:read_personal"

/**
 * A page with a field for providing the Coder deployment URL.
 *
 * Populates with the provided URL, at which point the user can accept or
 * enter their own.
 */
class DeploymentUrlStep(
    private val context: CoderToolboxContext,
    private val model: WizardModel,
) :
    WizardStep {
    private val urlField = TextField(context.i18n.ptrl("Deployment URL"), "", TextType.General)
    private val emptyLine = LabelField(context.i18n.pnotr(""), LabelStyleType.Normal)

    private val signatureFallbackStrategyField = CheckboxField(
        context.settingsStore.fallbackOnCoderForSignatures.isAllowed(),
        context.i18n.ptrl("Verify binary signature using releases.coder.com when CLI signatures are not available from the deployment")
    )

    override val panel: RowGroup
        get() {
            if (!context.settingsStore.disableSignatureVerification) {
                return RowGroup(
                    RowGroup.RowField(urlField),
                    RowGroup.RowField(emptyLine),
                    RowGroup.RowField(signatureFallbackStrategyField)
                )
            }
            return RowGroup(RowGroup.RowField(urlField))
        }

    override fun onVisible() {
        resetError()
        urlField.contentState.update {
            context.deploymentUrl.toString()
        }

        signatureFallbackStrategyField.checkedState.update {
            context.settingsStore.fallbackOnCoderForSignatures.isAllowed()
        }
    }

    override suspend fun onNext(): Boolean {
        context.settingsStore.updateSignatureFallbackStrategy(signatureFallbackStrategyField.checkedState.value)
        val rawUrl = urlField.contentState.value
        if (rawUrl.isBlank()) {
            reportError(context.i18n.ptrl("URL is required"))
            return false
        }

        try {
            model.url = validateRawUrl(rawUrl)
        } catch (e: MalformedURLException) {
            reportError(context.i18n.pnotr("URL is invalid: ${e.message}"))
            return false
        }

        if (context.settingsStore.requiresMTlsAuth) {
            model.goToLast()
            return true
        }
        if (context.settingsStore.requiresTokenAuth && context.settingsStore.preferOAuth2IfAvailable) {
            try {
                context.logger.info("Prefers OAuth2 authentication")
                model.oauthSession = handleOAuth2(rawUrl)
                return false
            } catch (e: Exception) {
                reportError(context.i18n.pnotr("Failed to authenticate with OAuth2: ${e.message}"))
                return false
            }
        }
        // if all else fails try the good old API token auth
        model.goToNext()
        return true
    }

    private suspend fun handleOAuth2(urlString: String): CoderOAuthSessionContext? {
        val oauth2Client = OAuth2Client(context)
        val oauth2ServerMetadata = oauth2Client.discoverMetadata(urlString) ?: return null

        context.logger.debug("registering coder-jetbrains-toolbox as client app")
        val clientResponse = oauth2Client.registerClient(
            oauth2ServerMetadata.registrationEndpoint,
            ClientRegistrationRequest(
                clientName = "coder-jetbrains-toolbox",
                redirectUris = listOf(REDIRECT_URI),
                grantTypes = listOf("authorization_code", "refresh_token"),
                responseTypes = oauth2ServerMetadata.supportedResponseTypes,
                scope = OAUTH2_SCOPE,
                tokenEndpointAuthMethod = if (oauth2ServerMetadata.authMethodForTokenEndpoint.contains(
                        TokenEndpointAuthMethod.CLIENT_SECRET_BASIC
                    )
                ) {
                    "client_secret_basic"
                } else if (oauth2ServerMetadata.authMethodForTokenEndpoint.contains(TokenEndpointAuthMethod.CLIENT_SECRET_POST)) {
                    "client_secret_post"
                } else {
                    "none"
                }
            )
        )

        val codeVerifier = PKCEGenerator.generateCodeVerifier()
        val codeChallenge = PKCEGenerator.generateCodeChallenge(codeVerifier)
        val state = UUID.randomUUID().toString()

        val loginUrl = oauth2ServerMetadata.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientResponse.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("scope", OAUTH2_SCOPE)
            .addQueryParameter("state", state)
            .build()
            .toString()

        context.logger.info("Launching browser for OAuth2 authentication")
        context.desktop.browse(loginUrl) {
            context.ui.showErrorInfoPopup(it)
        }

        return CoderOAuthSessionContext(
            clientId = clientResponse.clientId,
            clientSecret = clientResponse.clientSecret,
            tokenCodeVerifier = codeVerifier,
            state = state,
            tokenEndpoint = oauth2ServerMetadata.tokenEndpoint,
            tokenAuthMethod = oauth2ServerMetadata.authMethodForTokenEndpoint.getPreferredOrAvailable()
        )
    }

    /**
     * Throws [MalformedURLException] if the given string violates RFC-2396
     */
    private fun validateRawUrl(url: String): URL {
        try {
            val result = url.validateStrictWebUrl()
            if (result is Invalid) {
                throw MalformedURLException(result.reason)
            }
            return url.toURL()
        } catch (e: Exception) {
            throw MalformedURLException(e.message)
        }
    }

    override fun onBack() {
        // it's the first step. Can't go anywhere back from here
    }

    private fun reportError(message: LocalizableString?) {
        if (message != null) {
            context.logger.info(message.toString())
            urlField.modifiers.value = listOf(FieldModifier.LocalizableError(message))
        } else {
            resetError()
        }
    }

    private fun resetError() {
        urlField.modifiers.value = emptyList()
    }
}
