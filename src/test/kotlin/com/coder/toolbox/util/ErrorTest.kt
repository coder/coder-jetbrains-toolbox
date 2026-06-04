package com.coder.toolbox.util

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

internal class ErrorTest {
    @Test
    fun `sanitizeSecrets redacts common token forms`() {
        val raw = """
            env={CODER_SESSION_TOKEN=super-secret-token}
            header=Coder-Session-Token: super-secret-token
            argv=--token super-secret-token
            argv=--token=super-secret-token
            uri=https://coder.example.com?token=super-secret-token
        """.trimIndent()

        val sanitized = raw.sanitizeSecrets()

        assertContains(sanitized, "CODER_SESSION_TOKEN=<redacted>")
        assertContains(sanitized, "Coder-Session-Token: <redacted>")
        assertContains(sanitized, "--token <redacted>")
        assertContains(sanitized, "--token=<redacted>")
        assertContains(sanitized, "?token=<redacted>")
        assertFalse(sanitized.contains("super-secret-token"))
    }

    @Test
    fun `process exit exception redacts output in message`() {
        val ex = ProcessExitException(
            ProcessResult(
                command = listOf("coder", "login"),
                exitCode = 1,
                stdout = "CODER_SESSION_TOKEN=super-secret-token",
                stderr = "Coder-Session-Token: super-secret-token",
            ),
            expectedExitCodes = 0..0,
        )

        assertContains(ex.message.orEmpty(), "CODER_SESSION_TOKEN=<redacted>")
        assertContains(ex.message.orEmpty(), "Coder-Session-Token: <redacted>")
        assertFalse(ex.message.orEmpty().contains("super-secret-token"))
    }
}
