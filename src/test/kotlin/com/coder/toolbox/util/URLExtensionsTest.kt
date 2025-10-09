package com.coder.toolbox.util

import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class URLExtensionsTest {
    @Test
    fun testToURL() {
        assertEquals(
            URL("https", "localhost", 8080, "/path"),
            "https://localhost:8080/path".toURL(),
        )
    }

    @Test
    fun testWithPath() {
        assertEquals(
            URL("https", "localhost", 8080, "/foo/bar"),
            URL("https", "localhost", 8080, "/").withPath("/foo/bar"),
        )

        assertEquals(
            URL("https", "localhost", 8080, "/foo/bar"),
            URL("https", "localhost", 8080, "/old/path").withPath("/foo/bar"),
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
    fun `valid https URL with path and query should return Valid`() {
        val result = "https://coder.com/bin/coder-linux-amd64?query=1".validateStrictWebUrl()
        assertEquals(WebUrlValidationResult.Valid, result)
    }

    @Test
    fun `relative URL should return Invalid with appropriate message`() {
        val url = "/bin/coder-linux-amd64"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The URL \"/bin/coder-linux-amd64\" is missing a scheme (like https://). Please enter a full web address like \"https://example.com\""),
            result
        )
    }

    @Test
    fun `opaque URI like mailto should return Invalid`() {
        val url = "mailto:user@coder.com"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The URL \"mailto:user@coder.com\" is invalid because it is not in the standard format. Please enter a full web address like \"https://example.com\""),
            result
        )
    }

    @Test
    fun `unsupported scheme like ftp should return Invalid`() {
        val url = "ftp://coder.com"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The URL \"ftp://coder.com\" must start with http:// or https://, not \"ftp\""),
            result
        )
    }

    @Test
    fun `http URL with missing authority should return Invalid`() {
        val url = "http:///bin/coder-linux-amd64"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The URL \"http:///bin/coder-linux-amd64\" does not include a valid website name. Please enter a full web address like \"https://example.com\""),
            result
        )
    }

    @Test
    fun `malformed URI should return Invalid with parsing error message`() {
        val url = "http://[invalid-uri]"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The input \"http://[invalid-uri]\" is not a valid web address. Please enter a full web address like \"https://example.com\""),
            result
        )
    }

    @Test
    fun `URI without colon should return Invalid as URI is not absolute`() {
        val url = "http//coder.com"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The URL \"http//coder.com\" is missing a scheme (like https://). Please enter a full web address like \"https://example.com\""),
            result
        )
    }

    @Test
    fun `URI without double forward slashes should return Invalid because the URI is not hierarchical`() {
        val url = "http:coder.com"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The URL \"http:coder.com\" is invalid because it is not in the standard format. Please enter a full web address like \"https://example.com\""),
            result
        )
    }

    @Test
    fun `URI without a single forward slash should return Invalid because the URI does not have a hostname`() {
        val url = "https:/coder.com"
        val result = url.validateStrictWebUrl()
        assertEquals(
            WebUrlValidationResult.Invalid("The URL \"https:/coder.com\" does not include a valid website name. Please enter a full web address like \"https://example.com\""),
            result
        )
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
