package com.coder.toolbox.oauth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface CoderAuthorizationApi {
    @GET(".well-known/oauth-authorization-server")
    suspend fun discoveryMetadata(): Response<AuthorizationServer>

    @POST("oauth2/register")
    suspend fun registerClient(
        @Body request: ClientRegistrationRequest
    ): Response<ClientRegistrationResponse>
}