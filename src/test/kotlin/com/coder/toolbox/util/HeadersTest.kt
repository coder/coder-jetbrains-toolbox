package com.coder.toolbox.util

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class HeadersTest {
    @Test
    @IgnoreOnWindows
    fun testGetHeadersOK() {
        val tests =
            mapOf(
                null to emptyMap(),
                "" to emptyMap(),
                "printf 'foo=bar\\nbaz=qux'" to mapOf("foo" to "bar", "baz" to "qux"),
                "printf 'foo=bar\\r\\nbaz=qux'" to mapOf("foo" to "bar", "baz" to "qux"),
                "printf 'foo=bar\\r\\n'" to mapOf("foo" to "bar"),
                "printf 'foo=bar'" to mapOf("foo" to "bar"),
                "printf 'foo=bar='" to mapOf("foo" to "bar="),
                "printf 'foo=bar=baz'" to mapOf("foo" to "bar=baz"),
                "printf 'foo='" to mapOf("foo" to ""),
                "printf 'foo=bar   '" to mapOf("foo" to "bar   "),
                "exit 0" to mapOf(),
                "printf ''" to mapOf(),
                "printf 'ignore me' >&2" to mapOf(),
                "printf 'foo=bar' && printf 'ignore me' >&2" to mapOf("foo" to "bar"),
            )
        tests.forEach {
            assertEquals(
                it.value,
                getHeaders(URL("http://localhost"), it.key),
            )
        }
    }

    @Test
    @IgnoreOnWindows
    fun testGetHeadersFail() {
        val tests =
            mapOf(
                "printf '=foo'" to "Header name is missing in \"=foo\"",
                "printf 'foo'" to "Header \"foo\" does not have two parts",
                "printf '  =foo'" to "Header name is missing in \"  =foo\"",
                "printf 'foo  =bar'" to "Header name cannot contain spaces, got \"foo  \"",
                "printf 'foo  foo=bar'" to "Header name cannot contain spaces, got \"foo  foo\"",
                "printf '  foo=bar  '" to "Header name cannot contain spaces, got \"  foo\"",
                "exit 1" to "Unexpected exit value: 1",
                "printf 'foobar' >&2 && exit 1" to "foobar",
                "printf 'foo=bar\\r\\n\\r\\n'" to "Blank lines are not allowed",
                "printf '\\r\\nfoo=bar'" to "Blank lines are not allowed",
                "printf '\\r\\n'" to "Blank lines are not allowed",
                "printf 'f=b\\r\\n\\r\\nb=q'" to "Blank lines are not allowed",
            )
        tests.forEach {
            val ex =
                assertFailsWith(
                    exceptionClass = Exception::class,
                    block = { getHeaders(URL("http://localhost"), it.key) },
                )
            assertContains(ex.message.toString(), it.value)
        }
    }

    @Test
    @IgnoreOnUnix
    fun testGetHeadersOKOnWindows() {
        val tests =
            mapOf(
                null to emptyMap(),
                "" to emptyMap(),
                // No spaces around && to avoid trailing-space artifacts from cmd echo.
                "echo foo=bar&&echo baz=qux" to mapOf("foo" to "bar", "baz" to "qux"),
                "echo foo=bar" to mapOf("foo" to "bar"),
                "echo foo=bar=" to mapOf("foo" to "bar="),
                "echo foo=bar=baz" to mapOf("foo" to "bar=baz"),
                "echo foo=" to mapOf("foo" to ""),
                // type nul outputs 0 bytes → treated as empty output → empty map.
                "type nul" to mapOf(),
                "exit /b 0" to mapOf(),
                // >&2 redirects stdout to stderr; stdout stays empty.
                "echo ignore me>&2" to mapOf(),
                "echo foo=bar&&echo ignore me>&2" to mapOf("foo" to "bar"),
            )
        tests.forEach {
            assertEquals(
                it.value,
                getHeaders(URL("http://localhost"), it.key),
            )
        }
    }

    @Test
    @IgnoreOnUnix
    fun testGetHeadersFailOnWindows() {
        val tests =
            mapOf(
                "echo =foo" to "Header name is missing in \"=foo\"",
                "echo foo" to "Header \"foo\" does not have two parts",
                // cmd.exe strips one space after the command name, so three spaces → two spaces in output.
                "echo   =foo" to "Header name is missing in \"  =foo\"",
                "echo foo  =bar" to "Header name cannot contain spaces, got \"foo  \"",
                "echo foo  foo=bar" to "Header name cannot contain spaces, got \"foo  foo\"",
                "echo   foo=bar  " to "Header name cannot contain spaces, got \"  foo\"",
                "exit /b 1" to "Unexpected exit value: 1",
                // "foobar" appears in the InvalidExitValueException message as part of the command string.
                "echo foobar>&2&&exit /b 1" to "foobar",
                // echo. outputs a bare CRLF; a blank line anywhere in the output is an error.
                "echo foo=bar&&echo." to "Blank lines are not allowed",
                "echo.&&echo foo=bar" to "Blank lines are not allowed",
                "echo." to "Blank lines are not allowed",
                "echo f=b&&echo.&&echo b=q" to "Blank lines are not allowed",
            )
        tests.forEach {
            val ex =
                assertFailsWith(
                    exceptionClass = Exception::class,
                    block = { getHeaders(URL("http://localhost"), it.key) },
                )
            assertContains(ex.message.toString(), it.value)
        }
    }

    @Test
    fun testSetsEnvironment() {
        val headers =
            if (getOS() == OS.WINDOWS) {
                getHeaders(URL("http://localhost12345"), "echo url=%CODER_URL%")
            } else {
                getHeaders(URL("http://localhost12345"), "printf url=\$CODER_URL")
            }
        assertEquals(mapOf("url" to "http://localhost12345"), headers)
    }
}
