package com.coder.toolbox.util

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class ProcessRunnerTest {
    @Test
    @IgnoreOnWindows
    fun `runProcess captures stdout and stderr`() {
        val result = runProcess(listOf("sh", "-c", "printf hello && printf problem >&2"))

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("problem", result.stderr)
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess can discard stderr on success`() {
        val result = runProcess(
            listOf("sh", "-c", "printf hello && printf problem >&2"),
            stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess includes stderr on failure when discarding stderr on success`() {
        val ex = assertFailsWith<ProcessExitException> {
            runProcess(
                listOf("sh", "-c", "printf problem >&2; exit 5"),
                stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
            )
        }

        assertEquals(5, ex.result.exitCode)
        assertEquals("problem", ex.result.stderr)
        assertContains(ex.message.orEmpty(), "problem")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess captures stdout and stderr on windows`() {
        val result = runProcess(listOf("cmd.exe", "/c", "echo hello&&echo problem 1>&2"))

        assertEquals(0, result.exitCode)
        assertContains(result.stdout, "hello")
        assertContains(result.stderr, "problem")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess can discard stderr on success on windows`() {
        val result = runProcess(
            listOf("cmd.exe", "/c", "echo hello&&echo problem 1>&2"),
            stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
        )

        assertEquals(0, result.exitCode)
        assertContains(result.stdout, "hello")
        assertEquals("", result.stderr)
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess includes stderr on failure when discarding stderr on success on windows`() {
        val ex = assertFailsWith<ProcessExitException> {
            runProcess(
                listOf("cmd.exe", "/c", "echo problem 1>&2&&exit /b 5"),
                stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
            )
        }

        assertEquals(5, ex.result.exitCode)
        assertContains(ex.result.stderr, "problem")
        assertContains(ex.message.orEmpty(), "problem")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess passes environment to child process on windows`() {
        val result = runProcess(
            listOf("cmd.exe", "/c", "echo token=%CODER_SESSION_TOKEN%"),
            mapOf("CODER_SESSION_TOKEN" to "token"),
        )

        assertEquals("token=token", result.stdout.trim())
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess accepts configured non-zero exit code on windows`() {
        val result = runProcess(
            listOf("cmd.exe", "/c", "echo expected failure&&exit /b 7"),
            expectedExitCodes = 0..7,
        )

        assertEquals(7, result.exitCode)
        assertContains(result.stdout, "expected failure")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess redacts labeled token values in failure messages on windows`() {
        val ex = assertFailsWith<ProcessExitException> {
            runProcess(
                listOf("cmd.exe", "/c", "echo CODER_SESSION_TOKEN=%CODER_SESSION_TOKEN% 1>&2&&exit /b 7"),
                mapOf("CODER_SESSION_TOKEN" to "super-secret-token"),
            )
        }

        assertContains(ex.message.orEmpty(), "CODER_SESSION_TOKEN=<redacted>")
        assertFalse(ex.message.orEmpty().contains("super-secret-token"))
    }

    @Test
    fun `runProcess passes environment to child process`() {
        val result =
            if (getOS() == OS.WINDOWS) {
                runProcess(
                    listOf("cmd.exe", "/c", "echo %CODER_SESSION_TOKEN%"),
                    mapOf("CODER_SESSION_TOKEN" to "token"),
                )
            } else {
                runProcess(
                    listOf("sh", "-c", "printf %s \"${'$'}CODER_SESSION_TOKEN\""),
                    mapOf("CODER_SESSION_TOKEN" to "token"),
                )
            }

        assertEquals("token", result.stdout.trim())
    }

    @Test
    fun `runProcess throws sanitized exception on unexpected exit`() {
        val ex =
            if (getOS() == OS.WINDOWS) {
                assertFailsWith<ProcessExitException> {
                    runProcess(
                        listOf("cmd.exe", "/c", "echo CODER_SESSION_TOKEN=%CODER_SESSION_TOKEN% 1>&2&&exit /b 7"),
                        mapOf("CODER_SESSION_TOKEN" to "super-secret-token"),
                    )
                }
            } else {
                assertFailsWith<ProcessExitException> {
                    runProcess(
                        listOf("sh", "-c", "printf 'CODER_SESSION_TOKEN=%s' \"${'$'}CODER_SESSION_TOKEN\" >&2; exit 7"),
                        mapOf("CODER_SESSION_TOKEN" to "super-secret-token"),
                    )
                }
            }

        assertEquals(7, ex.result.exitCode)
        assertFalse(ex.message.orEmpty().contains("super-secret-token"))
    }
}
