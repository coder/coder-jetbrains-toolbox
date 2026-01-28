package com.coder.toolbox.feed

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.ex.ResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection.HTTP_OK

/**
 * Service for fetching JetBrains IDE product feeds.
 *
 * This service fetches IDE product information from JetBrains data services,
 * parsing the response and extracting relevant IDE information.
 */
class JetBrainsFeedService(
    private val context: CoderToolboxContext,
    private val feedApi: JetBrainsFeedApi
) {
    companion object {
        private const val RELEASE_FEED_URL = "https://data.services.jetbrains.com/products?type=release"
        private const val EAP_FEED_URL = "https://data.services.jetbrains.com/products?type=eap"
    }

    /**
     * Fetch the release feed and return a list of IDEs.
     *
     * @return List of IDE objects from the release feed
     * @throws ResponseException if the request fails
     */
    suspend fun fetchReleaseFeed(): List<Ide> {
        return fetchFeed(RELEASE_FEED_URL, "release")
    }

    /**
     * Fetch the EAP (Early Access Program) feed and return a list of IDEs.
     *
     * @return List of IDE objects from the EAP feed
     * @throws ResponseException if the request fails
     */
    suspend fun fetchEapFeed(): List<Ide> {
        return fetchFeed(EAP_FEED_URL, "eap")
    }

    /**
     * Fetch a feed from the specified URL and parse it into a list of IDEs.
     *
     * @param url The URL to fetch from
     * @param feedType The type of feed (for logging and error messages)
     * @return List of IDE objects
     * @throws ResponseException if the request fails
     */
    private suspend fun fetchFeed(url: String, feedType: String): List<Ide> = withContext(Dispatchers.IO) {
        context.logger.info("Fetching $feedType feed from $url")

        val response = feedApi.fetchFeed(url)

        when (response.code()) {
            HTTP_OK -> {
                val products = response.body() ?: emptyList()
                context.logger.info("Successfully fetched ${products.size} products from $feedType feed")

                // Flatten all products and their releases into a list of Ide objects
                products.flatMap { product ->
                    product.releases.map { release ->
                        Ide.from(product, release)
                    }
                }
            }

            else -> {
                throw ResponseException(
                    "Failed to fetch $feedType feed from $url",
                    response.code()
                )
            }
        }
    }
}
