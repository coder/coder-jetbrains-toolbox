package com.coder.toolbox.feed

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit API for fetching JetBrains IDE product feeds.
 *
 * Fetches product information from data.services.jetbrains.com for both
 * release and EAP (Early Access Program) builds.
 */
interface JetBrainsFeedApi {
    /**
     * Fetch the product feed from the specified URL.
     *
     * @param url The full URL to fetch (e.g., https://data.services.jetbrains.com/products?type=release)
     * @return Response containing a list of IDE products
     */
    @GET
    suspend fun fetchFeed(@Url url: String): Response<List<IdeProduct>>
}
