package com.coder.toolbox.oauth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface CoderAuthorizationApi {
    @GET(".well-known/oauth-authorization-server")
    suspend fun discoveryMetadata(): Response<AuthorizationServer>

    @POST("oauth2/register")
    suspend fun registerClient(
        @Body request: ClientRegistrationRequest
    ): Response<ClientRegistrationResponse>

    @FormUrlEncoded
    @POST
    suspend fun refreshToken(
        @Url url: String,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String
    ): Response<OAuthTokenResponse>

    @FormUrlEncoded
    @POST
    suspend fun refreshToken(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String
    ): Response<OAuthTokenResponse>
}
