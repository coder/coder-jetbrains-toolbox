package com.coder.toolbox.cli

import com.coder.toolbox.util.OS
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class ProxyCommandBuilderTest {
    @Test
    fun `POSIX renderer quotes shell syntax and preserves literal arguments`() {
        val command = ProxyCommandBuilder(OS.LINUX)
            .arguments(
                listOf(
                    "/usr/bin/coder",
                    "--url",
                    "https://coder.example.com/a?x=1&y=2",
                    "--header-command",
                    "printf '%s' \$(unsafe)",
                )
            )
            .argument("--")
            .argument("owner/workspace.agent")
            .render()

        assertEquals(
            "/usr/bin/coder --url 'https://coder.example.com/a?x=1&y=2' " +
                    "--header-command 'printf '\\''%%s'\\'' \$(unsafe)' -- owner/workspace.agent",
            command,
        )
    }

    @Test
    fun `only explicit SSH tokens are expanded`() {
        val command = ProxyCommandBuilder(OS.LINUX)
            .argument("https://coder.example.com/a%20b")
            .sshToken("%h")
            .render()

        assertEquals("https://coder.example.com/a%%20b %h", command)
        assertFailsWith<IllegalArgumentException> {
            ProxyCommandBuilder(OS.LINUX).sshToken("%p")
        }
    }

    @Test
    fun `rendered POSIX command does not execute command substitutions`() {
        val sentinel = Files.createTempDirectory("proxy-command-test").resolve("executed")
        val payload = "\$(touch${'$'}{IFS}$sentinel)"
        val command = ProxyCommandBuilder(OS.LINUX)
            .argument("echo")
            .argument(payload)
            .render()

        val output = ProcessBuilder("/bin/sh", "-c", command).start().run {
            assertEquals(0, waitFor())
            inputStream.bufferedReader().readText()
        }

        assertEquals("$payload${System.lineSeparator()}", output)
        assertFalse(sentinel.toFile().exists())
    }

    @Test
    fun `renderer rejects config line delimiters`() {
        listOf("line\rbreak", "line\nbreak", "nul\u0000byte").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                ProxyCommandBuilder(OS.LINUX).argument(value).render()
            }
        }
    }

    @Test
    fun `Windows renderer quotes spaces and SSH percent tokens`() {
        assertEquals(
            "\"C:\\Program Files\\Coder\\coder.exe\" \"%%CODER_URL%%\"",
            ProxyCommandBuilder(OS.WINDOWS)
                .argument("C:\\Program Files\\Coder\\coder.exe")
                .argument("%CODER_URL%")
                .render(),
        )
    }

    @Test
    fun `Windows renderer doubles backslashes that precede a literal quote`() {
        // A trailing backslash right before the closing quote, or right before an
        // embedded quote, must be doubled so it escapes itself rather than the
        // quote character. Backslashes not followed by a quote stay as-is.
        assertEquals(
            "\"C:\\Foo\\\"Bar\\coder.exe\"",
            ProxyCommandBuilder(OS.WINDOWS)
                .argument("C:\\Foo\"Bar\\coder.exe")
                .render(),
        )

        // A trailing backslash before the closing quote is doubled too, e.g. a
        // directory path with a space (forcing quoting) that ends in a backslash.
        assertEquals(
            "\"C:\\Program Files\\\\\"",
            ProxyCommandBuilder(OS.WINDOWS)
                .argument("C:\\Program Files\\")
                .render(),
        )
    }
}
