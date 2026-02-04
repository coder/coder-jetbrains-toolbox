package com.coder.toolbox.oauth

import com.coder.toolbox.sdk.convertors.UUIDConverter
import com.coder.toolbox.sdk.v2.CoderV2RestFacade
import com.coder.toolbox.util.toURL
import com.jetbrains.toolbox.api.core.auth.AuthConfiguration
import com.jetbrains.toolbox.api.core.auth.ContentType
import com.jetbrains.toolbox.api.core.auth.ContentType.FORM_URL_ENCODED
import com.jetbrains.toolbox.api.core.auth.OAuthToken
import com.jetbrains.toolbox.api.core.auth.PluginAuthInterface
import com.jetbrains.toolbox.api.core.auth.RefreshConfiguration
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.URL

class CoderOAuthManager : PluginAuthInterface<CoderAccount, CoderOAuthCfg> {
    private val moshi = Moshi.Builder().add(UUIDConverter()).build()

    override fun serialize(account: CoderAccount): String {
        val adapter = moshi.adapter(CoderAccount::class.java)
        return adapter.toJson(account)
    }

    override fun deserialize(string: String): CoderAccount {
        val adapter = moshi.adapter(CoderAccount::class.java)
        return adapter.fromJson(string) ?: throw IllegalArgumentException("Invalid CoderAccount JSON")
    }

    override suspend fun createAccount(
        token: OAuthToken,
        config: AuthConfiguration
    ): CoderAccount {
        val user = fetchUser(token, config.baseUrl.toURL())
        return CoderAccount(
            user.id.toString(),
            user.username,
            config.baseUrl,
            config.tokenUrl,
            config.tokenParams["client_id"]!!,
            config.tokenParams["client_secret"]
        )
    }

    override suspend fun updateAccount(
        token: OAuthToken,
        account: CoderAccount
    ): CoderAccount {
        val user = fetchUser(token, account.baseUrl.toURL())
        return CoderAccount(
            user.id.toString(),
            user.username,
            account.baseUrl,
            account.refreshUrl,
            account.clientId,
            account.clientSecret
        )
    }

    private suspend fun fetchUser(token: OAuthToken, baseUrl: URL): com.coder.toolbox.sdk.v2.models.User {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", token.authorizationHeader)
                    .build()
                chain.proceed(request)
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val service = retrofit.create(CoderV2RestFacade::class.java)
        return service.me().body() ?: throw IllegalStateException("Could not fetch user info")
    }

    override fun createAuthConfig(loginConfiguration: CoderOAuthCfg): AuthConfiguration {
        val codeVerifier = PKCEGenerator.generateCodeVerifier()
        val codeChallenge = PKCEGenerator.generateCodeChallenge(codeVerifier)

        val tokenParams = buildMap {
            put("grant_type", "authorization_code")
            put("client_id", loginConfiguration.clientId)
            put("code_verifier", codeVerifier)

            loginConfiguration.clientSecret?.let {
                put("client_secret", it)
            }
        }
        return AuthConfiguration(
            authParams = mapOf(
                "client_id" to loginConfiguration.clientId,
                "response_type" to "code",
                "code_challenge" to codeChallenge
            ),
            tokenParams = tokenParams,
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
            override val refreshUrl: String = account.baseUrl
            override val parameters: Map<String, String> = buildMap {
                put("grant_type", "refresh_token")
                put("client_id", account.clientId)
                if (account.clientSecret != null) {
                    put("client_secret", account.clientSecret)
                }
            }
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
    val clientSecret: String? = null
)