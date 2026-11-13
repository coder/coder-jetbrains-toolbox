package com.coder.toolbox.util

import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class URLExtensionsTest {
    @Test
    fun `origins compare scheme canonical hostname and effective port`() {
        val url = "https://coder.example.com".toURL()
        assertTrue(url.hasSameOrigin("https://CODER.example.com:443/path".toURL()))
        assertFalse(url.hasSameOrigin("http://coder.example.com".toURL()))
        assertFalse(url.hasSameOrigin("https://coder.example.com:8443".toURL()))
        assertFalse(url.hasSameOrigin("https://other.example.com".toURL()))
        assertFalse(url.hasSameOrigin(null))
        assertFalse("file:///tmp".toURL().hasSameOrigin("file:///tmp".toURL()))
        assertEquals(
            "https://coder.example.com",
            "https://user:password@CODER.example.com:443/path?q=1#fragment".toURL().origin()
        )
    }

    @Test
    fun testToURL() {
        assertEquals(
            expected = URI.create("https://localhost:8080/path").toURL(),
            actual = "https://localhost:8080/path".toURL(),
        )
    }

    @Test
    fun testWithPath() {
        assertEquals(
            expected = "https://localhost:8080/foo/bar".toURL(),
            actual = "https://localhost:8080/".toURL().withPath("/foo/bar"),
        )

        assertEquals(
            expected = "https://localhost:8080/foo/bar".toURL(),
            actual = "https://localhost:8080/old/path".toURL().withPath("/foo/bar"),
        )
    }

    @Test
    fun testSafeHost() {
        assertEquals("foobar", URL("https://foobar:8080").safeHost())
        assertEquals("xn--18j4d", URL("https://ほげ").safeHost())
        assertEquals("test.xn--n28h.invalid", URL("https://test.😉.invalid").safeHost())
        assertEquals("dev.xn---coder-vx74e.com", URL("https://dev.😉-coder.com").safeHost())
    }

    @Test
    fun testToQueryParameters() {
        val tests =
            mapOf(
                "" to mapOf(),
                "?" to mapOf(),
                "&" to mapOf(),
                "?&" to mapOf(),
                "?foo" to mapOf("foo" to ""),
                "?foo=" to mapOf("foo" to ""),
                "?foo&" to mapOf("foo" to ""),
                "?foo=bar" to mapOf("foo" to "bar"),
                "?foo=bar&" to mapOf("foo" to "bar"),
                "?foo=bar&baz" to mapOf("foo" to "bar", "baz" to ""),
                "?foo=bar&baz=" to mapOf("foo" to "bar", "baz" to ""),
                "?foo=bar&baz=qux" to mapOf("foo" to "bar", "baz" to "qux"),
                "?foo=bar=bar2&baz=qux" to mapOf("foo" to "bar=bar2", "baz" to "qux"),
                // + decoded to space (application/x-www-form-urlencoded convention)
                "?foo=hello+world" to mapOf("foo" to "hello world"),
                // percent-encoded characters
                "?foo=hello%20world" to mapOf("foo" to "hello world"),
                "?foo=bar%3Dbaz" to mapOf("foo" to "bar=baz"),
                "?foo=caf%C3%A9" to mapOf("foo" to "café"),
                // OAuth cancel callback: error_description uses + for spaces
                "?error=access_denied&error_description=The+resource+owner+or+authorization+server+denied+the+request.&state=abc" to mapOf(
                    "error" to "access_denied",
                    "error_description" to "The resource owner or authorization server denied the request.",
                    "state" to "abc",
                ),
            )
        tests.forEach {
            assertEquals(
                it.value,
                URI("http://dev.coder.com" + it.key).toQueryParameters(),
            )
        }
    }

    @Test
    fun `valid http URL should return Valid`() {
        val result = "http://coder.com".validateStrictWebUrl()
        assertEquals(WebUrlValidationResult.Valid, result)
    }

    @Test
    fun `manual setup accepts base paths and links require an origin`() {
        val url = "https://coder.example.com/team/"
        assertEquals(WebUrlValidationResult.Valid, url.validateStrictWebUrl())
        assertTrue(url.validateStrictWebUrl(originOnly = true) is WebUrlValidationResult.Invalid)
        for (origin in listOf("http://localhost:3000", "https://[::1]:8443/", "https://ほげ.example/")) {
            assertEquals(WebUrlValidationResult.Valid, origin.validateStrictWebUrl(originOnly = true))
        }
    }

    @Test
    fun `invalid deployment URLs are rejected without displaying their values`() {
        for (url in listOf("/relative", "file:///tmp", "https:/missing", "https://host:99999",
            "https://user@host", "https://host/?private", "https://host/#private", "https://host/a%20b",
            "https://host/dollar$", "https://host/../team", "https://host;other")) {
            val result = url.validateStrictWebUrl()
            assertTrue(result is WebUrlValidationResult.Invalid)
            assertFalse(result.reason.contains(url))
        }
    }

    @Test
    fun `query components are split before decoding and decoded exactly once`() {
        val params = URI("jetbrains://gateway/coder?folder=a%26b%3Dc%2B%252F&owner=a+b").toQueryParameters()
        assertEquals(mapOf("folder" to "a&b=c+%2F", "owner" to "a b"), params)
        for (query in listOf("url=a&url=a", "state=a&%73tate=b", "token=%FF")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { URI("jetbrains://gateway/coder?$query").toQueryParameters() }
        }
    }

    @Test
    fun `returns base URL without path or query`() {
        val fullUrl = "https://example.com/path/to/page?param=1"
        val result = fullUrl.toBaseURL()
        assertEquals(URL("https://example.com"), result)
    }

    @Test
    fun `includes port if specified`() {
        val fullUrl = "https://example.com:8080/api/v1/resource"
        val result = fullUrl.toBaseURL()
        assertEquals(URL("https://example.com:8080"), result)
    }

    @Test
    fun `handles subdomains correctly`() {
        val fullUrl = "http://api.subdomain.example.org/v2/users"
        val result = fullUrl.toBaseURL()
        assertEquals(URL("http://api.subdomain.example.org"), result)
    }

    @Test
    fun `handles simple domain without path`() {
        val fullUrl = "https://test.com"
        val result = fullUrl.toBaseURL()
        assertEquals(URL("https://test.com"), result)
    }

    @Test
    fun `throws exception for invalid URL`() {
        assertFailsWith<IllegalArgumentException> { "ht!tp://bad_url".toBaseURL() }
    }
}
