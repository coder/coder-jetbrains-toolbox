package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.browser.browse
import com.coder.toolbox.oauth.AuthorizationServer
import com.coder.toolbox.oauth.ClientRegistrationRequest
import com.coder.toolbox.oauth.CoderAuthorizationApi
import com.coder.toolbox.oauth.PKCEGenerator
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.sdk.CoderHttpClientBuilder
import com.coder.toolbox.sdk.convertors.LoggingConverterFactory
import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.validateStrictWebUrl
import com.coder.toolbox.views.state.CoderCliSetupContext
import com.coder.toolbox.views.state.CoderCliSetupWizardState
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.ui.components.CheckboxField
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.LabelStyleType
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.MalformedURLException
import java.net.URL
import java.util.UUID

private const val REDIRECT_URI = "jetbrains://gateway/com.coder.toolbox/auth"
private const val OAUTH2_SCOPE: String =
    "coder:workspaces.operate coder:workspaces.delete coder:workspaces.access user:read"

/**
 * A page with a field for providing the Coder deployment URL.
 *\
 * Populates with the provided URL, at which point the user can accept or
 * enter their own.
 */
class DeploymentUrlStep(
    private val context: CoderToolboxContext,
    visibilityState: StateFlow<ProviderVisibilityState>,
) :
    WizardStep {
    private val errorReporter = ErrorReporter.create(context, visibilityState, this.javaClass)

    private val urlField = TextField(context.i18n.ptrl("Deployment URL"), "", TextType.General)
    private val emptyLine = LabelField(context.i18n.pnotr(""), LabelStyleType.Normal)

    private val signatureFallbackStrategyField = CheckboxField(
        context.settingsStore.fallbackOnCoderForSignatures.isAllowed(),
        context.i18n.ptrl("Verify binary signature using releases.coder.com when CLI signatures are not available from the deployment")
    )

    private val preferOAuth2IfAvailable = CheckboxField(
        true,
        context.i18n.ptrl("Prefer OAuth2 if available over authentication via API Key")
    )

    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    val okHttpClient = CoderHttpClientBuilder.default(context)

    override val panel: RowGroup
        get() {
            if (!context.settingsStore.disableSignatureVerification) {
                return RowGroup(
                    RowGroup.RowField(urlField),
                    RowGroup.RowField(emptyLine),
                    RowGroup.RowField(signatureFallbackStrategyField),
                    RowGroup.RowField(preferOAuth2IfAvailable),
                    RowGroup.RowField(errorField)
                )

            }
            return RowGroup(
                RowGroup.RowField(urlField),
                RowGroup.RowField(errorField)
            )
        }

    override fun onVisible() {
        errorField.textState.update {
            context.i18n.pnotr("")
        }
        urlField.contentState.update {
            context.deploymentUrl.toString()
        }

        signatureFallbackStrategyField.checkedState.update {
            context.settingsStore.fallbackOnCoderForSignatures.isAllowed()
        }
        errorReporter.flush()
    }

    override suspend fun onNext(): Boolean {
        context.settingsStore.updateSignatureFallbackStrategy(signatureFallbackStrategyField.checkedState.value)
        val rawUrl = urlField.contentState.value
        if (rawUrl.isBlank()) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return false
        }

        try {
            CoderCliSetupContext.url = validateRawUrl(rawUrl)
        } catch (e: MalformedURLException) {
            errorReporter.report("URL is invalid", e)
            return false
        }

        if (context.settingsStore.requiresMTlsAuth) {
            CoderCliSetupWizardState.goToLastStep()
            return true
        }
        if (context.settingsStore.requiresTokenAuth && preferOAuth2IfAvailable.checkedState.value) {
            try {
                CoderCliSetupContext.oauthSession = handleOAuth2(rawUrl)
                return false
            } catch (e: Exception) {
                errorReporter.report("Failed to check OAuth support: ${e.message}", e)
            }
        }
        // if all else fails try the good old API token auth
        CoderCliSetupWizardState.goToNextStep()
        return true
    }

    private suspend fun handleOAuth2(urlString: String): CoderOAuthSessionContext? {
        val service = createAuthorizationService(urlString)
        val authServer = fetchDiscoveryMetadata(service) ?: return null

        context.logger.debug("registering coder-jetbrains-toolbox as client app")
        val clientResponse = registerClient(service, authServer) ?: return null

        val codeVerifier = PKCEGenerator.generateCodeVerifier()
        val codeChallenge = PKCEGenerator.generateCodeChallenge(codeVerifier)
        val state = UUID.randomUUID().toString()

        val loginUrl = authServer.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientResponse.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("scope", OAUTH2_SCOPE)
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
            tokenEndpoint = authServer.tokenEndpoint
        )
    }

    private fun createAuthorizationService(urlString: String): CoderAuthorizationApi {
        return Retrofit.Builder()
            .baseUrl(urlString)
            .client(okHttpClient)
            .addConverterFactory(
                LoggingConverterFactory.wrap(
                    context,
                    MoshiConverterFactory.create(Moshi.Builder().build())
                )
            )
            .build()
            .create(CoderAuthorizationApi::class.java)
    }

    private suspend fun fetchDiscoveryMetadata(service: CoderAuthorizationApi): AuthorizationServer? {
        val response = service.discoveryMetadata()
        if (response.isSuccessful) {
            return response.body()
        }
        context.logger.info("OAuth discovery failed: ${response.code()} ${response.message()} || ${response.errorBody()}")
        return null
    }

    private suspend fun registerClient(
        service: CoderAuthorizationApi,
        authServer: AuthorizationServer
    ): com.coder.toolbox.oauth.ClientRegistrationResponse? {
        // TODO - until https://github.com/coder/coder/issues/20370 is delivered
        val clientResponse = service.registerClient(
            ClientRegistrationRequest(
                clientName = "coder-jetbrains-toolbox",
                redirectUris = listOf(REDIRECT_URI),
                grantTypes = listOf("authorization_code", "refresh_token"),
                responseTypes = authServer.supportedResponseTypes,
                scope = OAUTH2_SCOPE,
                tokenEndpointAuthMethod = if (authServer.authMethodForTokenEndpoint.contains(TokenEndpointAuthMethod.CLIENT_SECRET_BASIC)) {
                    "client_secret_basic"
                } else if (authServer.authMethodForTokenEndpoint.contains(TokenEndpointAuthMethod.CLIENT_SECRET_POST)) {
                    "client_secret_post"
                } else {
                    "none"
                }
            )
        )

        if (clientResponse.isSuccessful) {
            return requireNotNull(clientResponse.body()) { "Successful response returned null body or client registration metadata" }
        } else {
            context.logger.error(">> ${clientResponse.code()} ${clientResponse.message()} || ${clientResponse.errorBody()}")
            return null
        }
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
}
