package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderSshLogCollectorTest {
    @TempDir
    lateinit var root: Path

    private val logs get() = root.resolve("logs")
    private val diagnostics get() = root.resolve("diagnostics")

    private fun collector() = CoderSshLogCollector(mockk(relaxed = true), logs)

    @Test
    fun `only the newest log for the active SSH process bypasses the age cutoff`() = runTest {
        Files.createDirectories(logs)
        val activeName = "coder-ssh-20261010-120000-abcde-12345.log"
        val oldNames = listOf(
            activeName,
            "coder-ssh-20261009-120000-abcde-12345.log",
            "$activeName.1",
            "coder-ssh-20261010-120000-abcde-67890.log",
            "coder-ssh-20261010-120000-abcde-112345.log",
            "12345.log",
        )
        val oldTime = FileTime.from(Instant.now().minus(Duration.ofDays(4)))
        for (name in oldNames) {
            val file = Files.writeString(logs.resolve(name), name)
            Files.setLastModifiedTime(file, oldTime)
        }
        Files.writeString(logs.resolve("coder-ssh-recent.log"), "recent")

        collector().collect(diagnostics, activeSshPid = 12345)

        val collected = Files.list(diagnostics.resolve("coder-ssh")).use { files ->
            files.map { it.fileName.toString() }.toList().toSet()
        }
        assertEquals(setOf(activeName, "coder-ssh-recent.log"), collected)
        assertEquals(activeName, Files.readString(diagnostics.resolve("coder-ssh/$activeName")))
    }

    @Test
    fun `active PID-only logs bypass the age cutoff`() = runTest {
        Files.createDirectories(logs)
        val source = Files.writeString(logs.resolve("12345.log"), "active session")
        Files.setLastModifiedTime(source, FileTime.from(Instant.now().minus(Duration.ofDays(4))))

        collector().collect(diagnostics, activeSshPid = 12345)

        assertEquals("active session", Files.readString(diagnostics.resolve("coder-ssh/12345.log")))
    }

    @Test
    fun `collects recent CLI logs including PID and rotated logs only from the directory itself`() = runTest {
        Files.createDirectories(logs)
        val expected = mapOf(
            "coder-ssh-20261015-120000-abcde-123.log" to "session",
            "coder-ssh-session.log.1" to "rotated",
            "12345.log" to "older CLI",
        )
        for ((name, contents) in expected) Files.writeString(logs.resolve(name), contents)
        for (name in listOf("other.log", "secret.env", "12345.log.1", "coder-ssh.log.zip")) {
            Files.writeString(logs.resolve(name), "unrelated")
        }
        val old = Files.writeString(logs.resolve("coder-ssh-old.log"), "old")
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(4))))
        Files.createDirectories(logs.resolve("nested"))
        Files.writeString(logs.resolve("nested/coder-ssh-nested.log"), "nested")
        Files.createDirectory(logs.resolve("coder-ssh-directory.log"))

        collector().collect(diagnostics)

        val actual = Files.list(diagnostics.resolve("coder-ssh")).use { files ->
            files.toList().associate { it.fileName.toString() to Files.readString(it) }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `missing or empty log directory leaves other diagnostics available`() = runTest {
        Files.createDirectories(diagnostics)
        Files.writeString(diagnostics.resolve("coder-support.zip"), "bundle")
        collector().collect(diagnostics)
        Files.createDirectories(logs)
        collector().collect(diagnostics)
        assertEquals("bundle", Files.readString(diagnostics.resolve("coder-support.zip")))
        assertFalse(Files.exists(diagnostics.resolve("coder-ssh")))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `skips symbolic links to files and directories`() = runTest {
        Files.createDirectories(logs)
        val secret = Files.writeString(root.resolve("secret.txt"), "secret")
        Files.createSymbolicLink(logs.resolve("coder-ssh-secret-12345.log"), secret)
        Files.createSymbolicLink(logs.resolve("coder-ssh-directory-12345.log"), root)
        collector().collect(diagnostics, activeSshPid = 12345)
        assertFalse(Files.exists(diagnostics.resolve("coder-ssh")))
    }

    @Test
    fun `one failed copy does not prevent collecting other logs`() = runTest {
        Files.createDirectories(logs)
        val source = Files.writeString(logs.resolve("coder-ssh-blocked.log"), "blocked")
        Files.writeString(logs.resolve("coder-ssh-readable.log"), "readable")
        val blocked = Files.createDirectories(diagnostics.resolve("coder-ssh/coder-ssh-blocked.log"))
        Files.writeString(blocked.resolve("keep.txt"), "existing diagnostics")
        val delegate = mockk<Logger>(relaxed = true)
        val logger = CoderLogger(delegate, mockk(), backgroundScope, mockk())
        val sessionId = SessionId.generate()

        CoderSshLogCollector(logger, logs).collect(diagnostics, sessionId)

        assertEquals("readable", Files.readString(diagnostics.resolve("coder-ssh/coder-ssh-readable.log")))
        assertEquals("existing diagnostics", Files.readString(blocked.resolve("keep.txt")))
        verify(exactly = 1) {
            delegate.warn(any<Throwable>(), "client_session_id=$sessionId Could not collect SSH log $source")
        }
    }

    @Test
    fun `copies only the last 50 MiB of an old active log without changing the source`() = runTest {
        Files.createDirectories(logs)
        val source = logs.resolve("coder-ssh-large-12345.log")
        val limit = 50L * 1024 * 1024
        RandomAccessFile(source.toFile(), "rw").use {
            it.writeBytes("head")
            it.seek(limit)
            it.writeBytes("tail")
        }
        Files.setLastModifiedTime(source, FileTime.from(Instant.now().minus(Duration.ofDays(4))))

        collector().collect(diagnostics, activeSshPid = 12345)

        val output = diagnostics.resolve("coder-ssh/coder-ssh-large-12345.log")
        assertEquals(limit, Files.size(output))
        RandomAccessFile(output.toFile(), "r").use {
            assertEquals(0, it.read())
            it.seek(limit - 4)
            assertEquals("tail", it.readLine())
        }
        assertEquals(limit + 4, Files.size(source))
        RandomAccessFile(source.toFile(), "r").use { assertEquals('h'.code, it.read()) }
    }

    @Test
    fun `cancellation propagates without collecting SSH logs`() = runTest {
        Files.createDirectories(logs)
        Files.writeString(logs.resolve("coder-ssh-session.log"), "SSH session")
        launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { collector().collect(diagnostics) }
        }.join()
        assertFalse(Files.exists(diagnostics))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `unreadable files do not prevent collecting other logs`() = runTest {
        Files.createDirectories(logs)
        val unreadable = Files.writeString(logs.resolve("coder-ssh-unreadable.log"), "unreadable")
        Files.writeString(logs.resolve("coder-ssh-readable.log"), "readable")
        val permissions = Files.getPosixFilePermissions(unreadable)
        try {
            Files.setPosixFilePermissions(unreadable, emptySet())
            collector().collect(diagnostics)
            assertTrue(Files.exists(diagnostics.resolve("coder-ssh/coder-ssh-readable.log")))
            if (!Files.isReadable(unreadable)) {
                assertFalse(Files.exists(diagnostics.resolve("coder-ssh/coder-ssh-unreadable.log")))
            }
        } finally {
            Files.setPosixFilePermissions(unreadable, permissions)
        }
    }
}
