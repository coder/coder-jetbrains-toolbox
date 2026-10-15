package com.coder.toolbox.diagnostics

import com.coder.toolbox.cli.CoderCLIManager.Companion.getSshLogDirectory
import com.coder.toolbox.session.SessionId
import com.coder.toolbox.util.OS
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderSupportBundleCollectorTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `unavailable diagnostics directory stops collection and logs one warning`() = runTest {
        val destination = Files.writeString(root.resolve("diagnostics"), "existing file")
        val sshLogs = Files.createDirectories(root.resolve("ssh-logs"))
        val sshLog = Files.writeString(sshLogs.resolve("coder-ssh-session.log"), "SSH session")
        val logger = mockk<CoderLogger>(relaxed = true)
        var bundleCollected = false
        val collector = CoderSupportBundleCollector(logger, sshLogs) {
            bundleCollected = true
        }

        collector.collectAdditionalDiagnostics(destination)

        assertFalse(bundleCollected)
        assertEquals("existing file", Files.readString(destination))
        assertEquals("SSH session", Files.readString(sshLog))
        verify(exactly = 1) { logger.warn(any<Throwable>(), "Could not collect Coder workspace diagnostics") }
        confirmVerified(logger)
    }

    @Test
    fun `writes the bundle inside Toolbox diagnostics`() = runTest {
        val sshLogs = Files.createDirectories(root.resolve("ssh-logs"))
        Files.writeString(sshLogs.resolve("coder-ssh-session.log"), "SSH session")
        val collector = CoderSupportBundleCollector(mockk(relaxed = true), sshLogs) {
            Files.writeString(it, "bundle")
        }
        collector.collectAdditionalDiagnostics(root.resolve("diagnostics"))
        assertEquals("bundle", Files.readString(root.resolve("diagnostics/coder-support.zip")))
        assertEquals("SSH session", Files.readString(root.resolve("diagnostics/coder-ssh/coder-ssh-session.log")))
    }

    @Test
    fun `collects SSH logs from the CLI default when no log directory is configured`() = runTest {
        val sshLogs = Files.createDirectories(root.resolve(".local/state/coder/logs"))
        Files.writeString(sshLogs.resolve("coder-ssh-default.log"), "default SSH session")
        val collector = CoderSupportBundleCollector(
            mockk(relaxed = true),
            getSshLogDirectory(null, emptyMap(), OS.LINUX, root),
        ) { Files.writeString(it, "bundle") }

        collector.collectAdditionalDiagnostics(root.resolve("diagnostics"))

        assertEquals(
            "default SSH session",
            Files.readString(root.resolve("diagnostics/coder-ssh/coder-ssh-default.log")),
        )
    }

    @Test
    fun `failure removes partial bundle and preserves other diagnostics without leaking error details`() = runTest {
        Files.writeString(root.resolve("other.log"), "other diagnostics")
        val sshLogs = Files.createDirectories(root.resolve("ssh-logs"))
        Files.writeString(sshLogs.resolve("12345.log"), "SSH session")
        val delegate = mockk<Logger>(relaxed = true)
        val logger = CoderLogger(delegate, mockk(), backgroundScope, mockk())
        val sessionId = SessionId.generate()
        val collector = CoderSupportBundleCollector(logger, sshLogs, currentSessionId = { sessionId }) {
            Files.writeString(it, "partial")
            error("sensitive command output")
        }
        collector.collectAdditionalDiagnostics(root)
        assertFalse(Files.exists(root.resolve("coder-support.zip")))
        assertEquals("other diagnostics", Files.readString(root.resolve("other.log")))
        val report = Files.readString(root.resolve("coder-support-error.txt"))
        assertTrue(report.contains("IllegalStateException"))
        assertFalse(report.contains("sensitive command output"))
        assertEquals("SSH session", Files.readString(root.resolve("coder-ssh/12345.log")))
        verify(exactly = 1) {
            delegate.warn(any<Throwable>(), "client_session_id=$sessionId Could not collect the Coder support bundle")
        }
    }

    @Test
    fun `cancellation propagates and removes partial output`() = runTest {
        val sshLogs = Files.createDirectories(root.resolve("ssh-logs"))
        Files.writeString(sshLogs.resolve("coder-ssh-session.log"), "SSH session")
        val collector = CoderSupportBundleCollector(mockk(relaxed = true), sshLogs) {
            Files.writeString(it, "partial")
            throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> { collector.collectAdditionalDiagnostics(root) }
        assertFalse(Files.exists(root.resolve("coder-support.zip")))
        assertFalse(Files.exists(root.resolve("coder-support-error.txt")))
        assertFalse(Files.exists(root.resolve("coder-ssh")))
    }

    @Test
    fun `SSH log failure preserves the support bundle`() = runTest {
        val invalidDirectory = Files.writeString(root.resolve("ssh-logs"), "not a directory")
        val delegate = mockk<Logger>(relaxed = true)
        val logger = CoderLogger(delegate, mockk(), backgroundScope, mockk())
        val sessionId = SessionId.generate()
        val collector = CoderSupportBundleCollector(logger, invalidDirectory, currentSessionId = { sessionId }) {
            Files.writeString(it, "bundle")
        }
        collector.collectAdditionalDiagnostics(root)
        assertEquals("bundle", Files.readString(root.resolve("coder-support.zip")))
        assertFalse(Files.exists(root.resolve("coder-support-error.txt")))
        verify(exactly = 1) {
            delegate.warn(
                any<Throwable>(),
                "client_session_id=$sessionId Could not collect Coder workspace diagnostics",
            )
        }
    }
}
