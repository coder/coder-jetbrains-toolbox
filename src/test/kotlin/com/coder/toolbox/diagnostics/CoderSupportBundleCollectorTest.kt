package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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
        val sshLog = Files.writeString(sshLogs.resolve("coder-ssh-session-1.log"), "SSH session")
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
    fun `appends SSH logs inside the bundle and preserves original entries and source logs`() = runTest {
        val sshLogs = Files.createDirectories(root.resolve("ssh-logs"))
        Files.writeString(sshLogs.resolve("coder-ssh-session-1.log"), "SSH session")
        val collector = CoderSupportBundleCollector(mockk(relaxed = true), sshLogs) {
            writeBundle(it)
        }
        collector.collectAdditionalDiagnostics(root.resolve("diagnostics"))
        ZipFile(root.resolve("diagnostics/coder-support.zip").toFile()).use { zip ->
            assertEquals("bundle", zip.getInputStream(zip.getEntry("agent.txt")).reader().readText())
            assertEquals(
                "SSH session",
                zip.getInputStream(zip.getEntry("toolbox-logs/ssh/coder-ssh-session-1.log")).reader().readText(),
            )
        }
        assertFalse(Files.exists(root.resolve("diagnostics/coder-ssh")))
        assertEquals("SSH session", Files.readString(sshLogs.resolve("coder-ssh-session-1.log")))
    }

    @Test
    fun `failure removes partial bundle and preserves other diagnostics without leaking error details`() = runTest {
        Files.writeString(root.resolve("other.log"), "other diagnostics")
        val sshLogs = Files.createDirectories(root.resolve("ssh-logs"))
        Files.writeString(sshLogs.resolve("coder-ssh-session-1.log"), "SSH session")
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
        assertEquals("SSH session", readSshLog(root.resolve("coder-ssh.zip")))
        verify(exactly = 1) {
            delegate.warn(any<Throwable>(), "client_session_id=$sessionId Could not collect the Coder support bundle")
        }
    }

    @Test
    fun `cancellation propagates and removes partial output`() = runTest {
        val sshLogs = Files.createDirectories(root.resolve("ssh-logs"))
        Files.writeString(sshLogs.resolve("coder-ssh-session-1.log"), "SSH session")
        val collector = CoderSupportBundleCollector(mockk(relaxed = true), sshLogs) {
            Files.writeString(it, "partial")
            throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> { collector.collectAdditionalDiagnostics(root) }
        assertFalse(Files.exists(root.resolve("coder-support.zip")))
        assertFalse(Files.exists(root.resolve("coder-support-error.txt")))
        assertFalse(Files.exists(root.resolve("coder-ssh.zip")))
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

    @Test
    fun `invalid bundle is untouched and SSH logs go into their own archive`() = runTest {
        val logs = Files.createDirectories(root.resolve("ssh-logs"))
        Files.writeString(logs.resolve("coder-ssh-session-1.log"), "session")
        val collector = CoderSupportBundleCollector(mockk(relaxed = true), logs) {
            Files.writeString(it, "invalid zip")
        }
        collector.collectAdditionalDiagnostics(root)
        assertEquals("invalid zip", Files.readString(root.resolve("coder-support.zip")))
        assertEquals("session", readSshLog(root.resolve("coder-ssh.zip")))
    }

    private fun readSshLog(archive: Path): String = ZipFile(archive.toFile()).use { zip ->
        zip.getInputStream(zip.getEntry("toolbox-logs/ssh/coder-ssh-session-1.log")).reader().readText()
    }

    private fun writeBundle(path: Path) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("agent.txt"))
            zip.write("bundle".toByteArray())
            zip.closeEntry()
        }
    }

}
