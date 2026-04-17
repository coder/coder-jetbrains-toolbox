package com.coder.toolbox.oauth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

interface CoderAuthorizationApi {
    @GET
    suspend fun discoverMetadata(
        @Url url: String
    ): Response<AuthorizationServer>

    @POST
    suspend fun registerClient(
        @Url url: String,
        @Body request: ClientRegistrationRequest
    ): Response<ClientRegistrationResponse>

    @POST
    @FormUrlEncoded
    suspend fun exchangeCode(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @FieldMap fields: Map<String, String>
    ): Response<OAuthTokenResponse>

    @POST
    @FormUrlEncoded
    suspend fun refreshToken(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @FieldMap fields: Map<String, String>
    ): Response<OAuthTokenResponse>
}
