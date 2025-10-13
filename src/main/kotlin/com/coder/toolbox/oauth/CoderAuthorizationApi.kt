package com.coder.toolbox.oauth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

interface CoderAuthorizationApi {
    @GET(".well-known/oauth-authorization-server")
    suspend fun discoveryMetadata(): Response<AuthorizationServer>

    @POST
    suspend fun registerClient(
        @Url url: String,
        @Body request: ClientRegistrationRequest
    ): Response<ClientRegistrationResponse>
}