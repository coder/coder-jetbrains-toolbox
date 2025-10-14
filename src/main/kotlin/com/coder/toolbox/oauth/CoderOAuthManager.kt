package com.coder.toolbox.oauth

import com.jetbrains.toolbox.api.core.auth.AuthConfiguration
import com.jetbrains.toolbox.api.core.auth.ContentType
import com.jetbrains.toolbox.api.core.auth.ContentType.FORM_URL_ENCODED
import com.jetbrains.toolbox.api.core.auth.OAuthToken
import com.jetbrains.toolbox.api.core.auth.PluginAuthInterface
import com.jetbrains.toolbox.api.core.auth.RefreshConfiguration

class CoderOAuthManager : PluginAuthInterface<CoderAccount, CoderOAuthCfg> {
    private lateinit var refreshConf: CoderRefreshConfig

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

    override fun createAuthConfig(loginConfiguration: CoderOAuthCfg): AuthConfiguration {
        val codeVerifier = PKCEGenerator.generateCodeVerifier()
        val codeChallenge = PKCEGenerator.generateCodeChallenge(codeVerifier)
        refreshConf = loginConfiguration.toRefreshConf()

        return AuthConfiguration(
            authParams = mapOf(
                "client_id" to loginConfiguration.clientId,
                "response_type" to "code",
                "code_challenge" to codeChallenge
            ),
            tokenParams = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to loginConfiguration.clientId,
                "code_verifier" to codeVerifier
            ),
            baseUrl = loginConfiguration.baseUrl,
            authUrl = loginConfiguration.authUrl,
            tokenUrl = loginConfiguration.tokenUrl,
            codeChallengeParamName = "code_challenge",
            codeChallengeMethod = "S256",
            verifierParamName = "code_verifier",
            authorization = null
        )
    }

    override fun createRefreshConfig(account: CoderAccount): RefreshConfiguration {
        return object : RefreshConfiguration {
            override val refreshUrl: String = refreshConf.refreshUrl
            override val parameters: Map<String, String> = mapOf(
                "grant_type" to "refresh_token",
                "client_id" to refreshConf.clientId,
                "client_secret" to refreshConf.clientSecret
            )
            override val authorization: String? = null
            override val contentType: ContentType = FORM_URL_ENCODED
        }
    }
}

data class CoderOAuthCfg(
    val baseUrl: String,
    val authUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String,
)

private data class CoderRefreshConfig(
    val refreshUrl: String,
    val clientId: String,
    val clientSecret: String,
)

private fun CoderOAuthCfg.toRefreshConf() = CoderRefreshConfig(
    refreshUrl = this.tokenUrl,
    clientId = this.clientId,
    this.clientSecret
)