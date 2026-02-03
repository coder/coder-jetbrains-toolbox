package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.browser.browse
import com.coder.toolbox.oauth.ClientRegistrationRequest
import com.coder.toolbox.oauth.CoderAuthorizationApi
import com.coder.toolbox.oauth.CoderOAuthCfg
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderHttpClientBuilder
import com.coder.toolbox.sdk.convertors.LoggingConverterFactory
import com.coder.toolbox.sdk.interceptors.Interceptors
import com.coder.toolbox.util.ReloadableTlsContext
import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.validateStrictWebUrl
import com.coder.toolbox.views.state.CoderCliSetupContext
import com.coder.toolbox.views.state.CoderCliSetupWizardState
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
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.MalformedURLException
import java.net.URL

/**
 * A page with a field for providing the Coder deployment URL.
 *
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

    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    val interceptors = buildList {
        add((Interceptors.userAgent(PluginManager.pluginInfo.version)))
        add(Interceptors.logging(context))
    }
    val okHttpClient = CoderHttpClientBuilder.build(
        context,
        interceptors,
        ReloadableTlsContext(context.settingsStore.readOnly().tls)
    )

    override val panel: RowGroup
        get() {
            if (!context.settingsStore.disableSignatureVerification) {
                return RowGroup(
                    RowGroup.RowField(urlField),
                    RowGroup.RowField(emptyLine),
                    RowGroup.RowField(signatureFallbackStrategyField),
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

    override fun onNext(): Boolean {
        context.settingsStore.updateSignatureFallbackStrategy(signatureFallbackStrategyField.checkedState.value)
        val url = urlField.textState.value
        if (url.isBlank()) {
            errorField.textState.update { context.i18n.ptrl("URL is required") }
            return false
        }
        try {
            CoderCliSetupContext.url = validateRawUrl(url)
        } catch (e: MalformedURLException) {
            errorReporter.report("URL is invalid", e)
            return false
        }
        val service = Retrofit.Builder()
            .baseUrl(CoderCliSetupContext.url!!)
            .client(okHttpClient)
            .addConverterFactory(
                LoggingConverterFactory.wrap(
                    context,
                    MoshiConverterFactory.create(Moshi.Builder().build())
                )
            )
            .build()
            .create(CoderAuthorizationApi::class.java)
        context.cs.launch {
            context.logger.info(">> checking if Coder supports OAuth2")
            val response = service.discoveryMetadata()
            if (response.isSuccessful) {
                val authServer = requireNotNull(response.body()) {
                    "Successful response returned null body or oauth server discovery metadata"
                }
                context.logger.info(">> registering coder-jetbrains-toolbox as client app $response")
                // TODO - until https://github.com/coder/coder/issues/20370 is delivered
                val clientResponse = service.registerClient(
                    ClientRegistrationRequest(
                        clientName = "coder-jetbrains-toolbox",
                        redirectUris = listOf("jetbrains://gateway/com.coder.toolbox/auth"),//URLEncoder.encode("jetbrains://gateway/com.coder.toolbox/oauth", StandardCharsets.UTF_8.toString())),
                        grantTypes = listOf("authorization_code", "refresh_token"),
                        responseTypes = authServer.supportedResponseTypes,
                        scope = "coder:workspaces.operate coder:workspaces.delete coder:workspaces.access user:read",
                        tokenEndpointAuthMethod = "client_secret_post"
                    )
                )
                if (clientResponse.isSuccessful) {
                    val clientResponse =
                        requireNotNull(clientResponse.body()) { "Successful response returned null body or client registration metadata" }
                    context.logger.info(">> initiating oauth login with $clientResponse")

                    val oauthCfg = CoderOAuthCfg(
                        baseUrl = CoderCliSetupContext.url!!.toString(),
                        authUrl = authServer.authorizationEndpoint,
                        tokenUrl = authServer.tokenEndpoint,
                        clientId = clientResponse.clientId,
                    )

                    val loginUrl = context.oauthManager.initiateLogin(oauthCfg)
                    context.logger.info(">> retrieving token")
                    context.desktop.browse(loginUrl) {
                        context.ui.showErrorInfoPopup(it)
                    }
                    val token = context.oauthManager.getToken("coder", forceRefresh = false)
                    context.logger.info(">> token is $token")
                } else {
                    context.logger.error(">> ${clientResponse.code()} ${clientResponse.message()} || ${clientResponse.errorBody()}")
                }
            }
        }

        if (context.settingsStore.requiresTokenAuth) {
            CoderCliSetupWizardState.goToNextStep()
        } else {
            CoderCliSetupWizardState.goToLastStep()
        }
        return true
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
