package com.coder.toolbox.oauth

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.CoderHttpClientBuilder
import com.coder.toolbox.sdk.convertors.LoggingConverterFactory
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.squareup.moshi.Moshi
import okhttp3.Credentials
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

private const val DISCOVERY_PATH = ".well-known/oauth-authorization-server"
private const val REGISTER_CLIENT_PATH = "oauth2/register"

class OAuth2Service(private val context: CoderToolboxContext) {
    private val service = createAuthorizationService()

    suspend fun discoverMetadata(baseUrl: String): AuthorizationServer? {
        val response = service.discoverMetadata("$baseUrl/$DISCOVERY_PATH")
        if (response.isSuccessful) {
            return response.body()
        }
        context.logger.info("OAuth discovery failed: ${response.code()} ${response.message()} || ${response.errorBody()}")
        return null
    }

    suspend fun registerClient(baseUrl: String, request: ClientRegistrationRequest): ClientRegistrationResponse? {
        // TODO - until https://github.com/coder/coder/issues/20370 is delivered
        val response = service.registerClient("$baseUrl/$REGISTER_CLIENT_PATH", request)

        if (response.isSuccessful) {
            return requireNotNull(response.body()) { "Successful response returned null body or client registration metadata" }
        } else {
            context.logger.info("Failed to register OAuth2 client: ${response.code()} ${response.message()} || ${response.errorBody()}")
            return null
        }
    }

    suspend fun exchangeCode(
        oauthSessionContext: CoderOAuthSessionContext,
        code: String
    ): OAuthTokenResponse {
        val auth = when (oauthSessionContext.tokenAuthMethod) {
            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC -> ClientAuth.ClientSecretBasic(
                oauthSessionContext.clientId,
                oauthSessionContext.clientSecret
            )

            TokenEndpointAuthMethod.CLIENT_SECRET_POST -> ClientAuth.ClientSecretPost(
                oauthSessionContext.clientId,
                oauthSessionContext.clientSecret
            )

            else -> ClientAuth.None(oauthSessionContext.clientId)
        }

        val response = service.exchangeCode(
            url = oauthSessionContext.tokenEndpoint,
            headers = auth.headers(),
            fields = auth.fields() + mapOf(
                "code" to code,
                "grant_type" to "authorization_code",
                "code_verifier" to oauthSessionContext.tokenCodeVerifier,
                "redirect_uri" to "jetbrains://gateway/com.coder.toolbox/auth"
            )
        )

        return handleResponse(response, "exchange code for token")
    }

    suspend fun refreshToken(oauthSessionContext: CoderOAuthSessionContext): OAuthTokenResponse {
        val refreshToken = oauthSessionContext.tokenResponse?.refreshToken
            ?: throw IllegalStateException("No refresh token available")

        val auth = when (oauthSessionContext.tokenAuthMethod) {
            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC -> ClientAuth.ClientSecretBasic(
                oauthSessionContext.clientId,
                oauthSessionContext.clientSecret
            )

            TokenEndpointAuthMethod.CLIENT_SECRET_POST -> ClientAuth.ClientSecretPost(
                oauthSessionContext.clientId,
                oauthSessionContext.clientSecret
            )

            else -> ClientAuth.None(oauthSessionContext.clientId)
        }

        val service = createAuthorizationService()
        val response = service.refreshToken(
            url = oauthSessionContext.tokenEndpoint,
            headers = auth.headers(),
            fields = auth.fields() + mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken
            )
        )

        return handleResponse(response, "refresh OAuth token")
    }

    private fun handleResponse(
        response: Response<OAuthTokenResponse>,
        action: String
    ): OAuthTokenResponse {
        if (!response.isSuccessful) {
            throw Exception("Failed to $action. Response code: ${response.code()} ${response.message()}")
        }

        return response.body() ?: throw Exception("Failed to $action. Response body is empty.")
    }

    private fun createAuthorizationService(): CoderAuthorizationApi {
        return Retrofit.Builder()
            .baseUrl("http://localhost/") // Placeholder, overridden by @Url
            .client(CoderHttpClientBuilder.default(context))
            .addConverterFactory(
                LoggingConverterFactory.wrap(
                    context,
                    MoshiConverterFactory.create(Moshi.Builder().build())
                )
            )
            .build()
            .create(CoderAuthorizationApi::class.java)
    }
}

private sealed interface ClientAuth {

    fun headers(): Map<String, String> = emptyMap()
    fun fields(): Map<String, String> = emptyMap()

    data class ClientSecretBasic(
        val clientId: String,
        val clientSecret: String
    ) : ClientAuth {
        override fun headers() = mapOf(
            "Authorization" to Credentials.basic(
                clientId,
                clientSecret
            )
        )
    }

    data class ClientSecretPost(
        val clientId: String,
        val clientSecret: String
    ) : ClientAuth {
        override fun fields() = mapOf(
            "client_id" to clientId,
            "client_secret" to clientSecret
        )
    }

    data class None(
        val clientId: String
    ) : ClientAuth {
        override fun fields() = mapOf(
            "client_id" to clientId
        )
    }
}
