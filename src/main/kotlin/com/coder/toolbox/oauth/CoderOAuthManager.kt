package com.coder.toolbox.oauth

import com.coder.toolbox.util.toBaseURL
import com.jetbrains.toolbox.api.core.auth.AuthConfiguration
import com.jetbrains.toolbox.api.core.auth.ContentType
import com.jetbrains.toolbox.api.core.auth.ContentType.FORM_URL_ENCODED
import com.jetbrains.toolbox.api.core.auth.OAuthToken
import com.jetbrains.toolbox.api.core.auth.PluginAuthInterface
import com.jetbrains.toolbox.api.core.auth.RefreshConfiguration

class CoderOAuthManager(
    private val clientId: String,
    private val authServer: AuthorizationServer
) : PluginAuthInterface<CoderAccount, CoderLoginCfg> {
    override fun serialize(account: CoderAccount): String = "${account.id}|${account.fullName}"

    override fun deserialize(string: String): CoderAccount = CoderAccount(
        string.split('|')[0],
        string.split('|')[1]
    )

    override suspend fun createAccount(
        token: OAuthToken,
        config: AuthConfiguration
    ): CoderAccount {
        TODO("Not yet implemented")
    }

    override suspend fun updateAccount(
        token: OAuthToken,
        account: CoderAccount
    ): CoderAccount {
        TODO("Not yet implemented")
    }

    override fun createAuthConfig(loginConfiguration: CoderLoginCfg): AuthConfiguration = AuthConfiguration(
        authParams = mapOf("response_type" to "code", "client_id" to clientId),
        tokenParams = mapOf("grant_type" to "authorization_code", "client_id" to clientId),
        baseUrl = authServer.authorizationEndpoint.toBaseURL().toString(),
        authUrl = authServer.authorizationEndpoint,
        tokenUrl = authServer.tokenEndpoint,
        codeChallengeParamName = "code_challenge",
        codeChallengeMethod = "S256",
        verifierParamName = "code_verifier",
        authorization = null
    )


    override fun createRefreshConfig(account: CoderAccount): RefreshConfiguration {
        return object : RefreshConfiguration {
            override val refreshUrl: String = authServer.tokenEndpoint
            override val parameters: Map<String, String> =
                mapOf("grant_type" to "refresh_token", "client_id" to clientId)
            override val authorization: String? = null
            override val contentType: ContentType = FORM_URL_ENCODED
        }
    }
}

object CoderLoginCfg