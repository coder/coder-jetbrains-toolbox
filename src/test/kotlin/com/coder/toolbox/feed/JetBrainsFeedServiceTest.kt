package com.coder.toolbox.feed

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.store.CoderSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

class JetBrainsFeedServiceTest {
    private lateinit var context: CoderToolboxContext
    private lateinit var settingsStore: CoderSettingsStore
    private lateinit var logger: Logger
    private lateinit var feedApi: JetBrainsFeedApi

    @BeforeEach
    fun setUp() {
        context = mockk<CoderToolboxContext>()
        settingsStore = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        feedApi = mockk()
        every { context.logger } returns logger
        every { context.settingsStore } returns settingsStore
    }

    private fun withFeedBaseUrl(url: String?) {
        every { settingsStore.readOnly() } returns settingsStore
        every { settingsStore.ideFeedBaseUrl } returns url
    }

    @Test
    fun `given no custom base URL when fetching feeds then it uses default JetBrains URL`() = runTest {
        withFeedBaseUrl(null)
        val service = JetBrainsFeedService(context, feedApi)
        coEvery { feedApi.fetchFeed(any()) } returns Response.success(emptyList())

        service.fetchReleaseFeed()
        service.fetchEapFeed()

        coVerify { feedApi.fetchFeed("https://data.services.jetbrains.com/products?type=release") }
        coVerify { feedApi.fetchFeed("https://data.services.jetbrains.com/products?type=eap") }
    }

    @Test
    fun `given a custom base URL when fetching feeds then it uses the custom URL`() = runTest {
        withFeedBaseUrl("https://ide-feed.internal.corp.com")
        val service = JetBrainsFeedService(context, feedApi)
        coEvery { feedApi.fetchFeed(any()) } returns Response.success(emptyList())

        service.fetchReleaseFeed()
        service.fetchEapFeed()

        coVerify { feedApi.fetchFeed("https://ide-feed.internal.corp.com/products?type=release") }
        coVerify { feedApi.fetchFeed("https://ide-feed.internal.corp.com/products?type=eap") }
    }

    @Test
    fun `given a custom base URL with trailing slash when fetching feeds then it trims the slash`() = runTest {
        withFeedBaseUrl("https://ide-feed.internal.corp.com/")
        val service = JetBrainsFeedService(context, feedApi)
        coEvery { feedApi.fetchFeed(any()) } returns Response.success(emptyList())

        service.fetchReleaseFeed()
        service.fetchEapFeed()

        coVerify { feedApi.fetchFeed("https://ide-feed.internal.corp.com/products?type=release") }
        coVerify { feedApi.fetchFeed("https://ide-feed.internal.corp.com/products?type=eap") }
    }

    @Test
    fun `given a blank base URL when fetching feeds then it falls back to default`() = runTest {
        withFeedBaseUrl("  ")
        val service = JetBrainsFeedService(context, feedApi)
        coEvery { feedApi.fetchFeed(any()) } returns Response.success(emptyList())

        service.fetchReleaseFeed()
        service.fetchEapFeed()

        coVerify { feedApi.fetchFeed("https://data.services.jetbrains.com/products?type=release") }
        coVerify { feedApi.fetchFeed("https://data.services.jetbrains.com/products?type=eap") }
    }

    @Test
    fun `given a custom base URL when release feed returns products then it parses them correctly`() = runTest {
        withFeedBaseUrl("https://ide-feed.internal.corp.com")
        val products = listOf(
            IdeProduct(
                "RustRover", "RR", "RustRover", listOf(
                    IdeRelease("241.1", "2024.1", IdeType.RELEASE, "2024-01-01")
                )
            )
        )
        val service = JetBrainsFeedService(context, feedApi)
        coEvery { feedApi.fetchFeed(any()) } returns Response.success(products)

        val result = service.fetchReleaseFeed()

        assert(result.size == 1)
        assert(result[0].code == "RR")
        assert(result[0].build == "241.1")
    }

    @Test
    fun `given a custom base URL when feed returns error then it throws ResponseException`() = runTest {
        withFeedBaseUrl("https://ide-feed.internal.corp.com")
        val service = JetBrainsFeedService(context, feedApi)
        coEvery { feedApi.fetchFeed(any()) } returns Response.error(500, "Server Error".toResponseBody())

        try {
            service.fetchReleaseFeed()
            assert(false) { "Expected ResponseException" }
        } catch (e: Exception) {
            assert(e.message?.contains("Failed to fetch release feed") == true)
        }
    }
}
