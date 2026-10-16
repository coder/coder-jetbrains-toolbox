package com.coder.toolbox.diagnostics

import io.mockk.mockk
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
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderSshLogCollectorTest {
    @TempDir
    lateinit var root: Path

    private val logs get() = root.resolve("logs")
    private val archive get() = root.resolve("archive.zip")

    private fun collector() = CoderSshLogCollector(mockk(relaxed = true), logs)

    private fun collected(): Map<String, String> {
        if (Files.notExists(archive)) return emptyMap()
        return ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().associate {
                it.name.removePrefix("toolbox-logs/ssh/") to zip.getInputStream(it).reader().readText()
            }
        }
    }

    @Test
    fun `logs of the active SSH process bypass the age cutoff`() = runTest {
        Files.createDirectories(logs)
        val activeName = "coder-ssh-20261010-120000-abcde-12345.log"
        val oldNames = listOf(
            activeName,
            "coder-ssh-20261010-120000-abcde-12345.log.1",
            "coder-ssh-20261010-120000-abcde-67890.log",
            "coder-ssh-20261010-120000-abcde-112345.log",
            "12345.log",
        )
        val oldTime = FileTime.from(Instant.now().minus(Duration.ofDays(4)))
        for (name in oldNames) {
            val file = Files.writeString(logs.resolve(name), name)
            Files.setLastModifiedTime(file, oldTime)
        }
        Files.writeString(logs.resolve("coder-ssh-recent-1.log"), "recent")

        collector().collect(archive, activeSshPid = 12345)

        assertEquals(mapOf(activeName to activeName, "coder-ssh-recent-1.log" to "recent"), collected())
    }

    @Test
    fun `PID-only logs are ignored even for the active PID`() = runTest {
        Files.createDirectories(logs)
        val source = Files.writeString(logs.resolve("12345.log"), "active session")
        Files.setLastModifiedTime(source, FileTime.from(Instant.now().minus(Duration.ofDays(4))))

        collector().collect(archive, activeSshPid = 12345)

        assertFalse(Files.exists(archive))
    }

    @Test
    fun `collects only recent PID-suffixed CLI logs from the directory itself`() = runTest {
        Files.createDirectories(logs)
        val expected = mapOf("coder-ssh-20261015-120000-abcde-123.log" to "session")
        for ((name, contents) in expected) Files.writeString(logs.resolve(name), contents)
        val unrelated = listOf(
            "12345.log", "other.log", "secret.env", "12345.log.1", "coder-ssh.log.zip",
            "coder-ssh-session.log", "coder-ssh-session-1.log.1",
        )
        for (name in unrelated) {
            Files.writeString(logs.resolve(name), "unrelated")
        }
        val old = Files.writeString(logs.resolve("coder-ssh-old-1.log"), "old")
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(4))))
        Files.createDirectories(logs.resolve("nested"))
        Files.writeString(logs.resolve("nested/coder-ssh-nested.log"), "nested")
        Files.createDirectory(logs.resolve("coder-ssh-directory-1.log"))

        collector().collect(archive)

        assertEquals(expected, collected())
    }

    @Test
    fun `missing or empty log directory does not create an archive`() = runTest {
        collector().collect(archive)
        Files.createDirectories(logs)
        collector().collect(archive)
        assertFalse(Files.exists(archive))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `skips symbolic links to files and directories`() = runTest {
        Files.createDirectories(logs)
        val secret = Files.writeString(root.resolve("secret.txt"), "secret")
        Files.createSymbolicLink(logs.resolve("coder-ssh-secret-12345.log"), secret)
        Files.createSymbolicLink(logs.resolve("coder-ssh-directory-12345.log"), root)
        collector().collect(archive, activeSshPid = 12345)
        assertFalse(Files.exists(archive))
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

        collector().collect(archive, activeSshPid = 12345)

        val output = ZipFile(archive.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("toolbox-logs/ssh/coder-ssh-large-12345.log")).readAllBytes()
        }
        assertEquals(limit, output.size.toLong())
        assertEquals(0, output.first().toInt())
        assertEquals("tail", String(output, output.size - 4, 4))
        assertEquals(limit + 4, Files.size(source))
        RandomAccessFile(source.toFile(), "r").use { assertEquals('h'.code, it.read()) }
    }

    private fun writeLogs(prefix: String, count: Int, minAge: Duration) {
        Files.createDirectories(logs)
        // Higher indexes are older, so the ordering between files is deterministic.
        for (index in 1..count) {
            val file = Files.writeString(logs.resolve("coder-ssh-$prefix-$index.log"), "log")
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(minAge.plusMinutes(index.toLong()))))
        }
    }

    private fun remainingLogs(prefix: String = ""): Int =
        Files.list(logs).use { files -> files.filter { prefix in it.fileName.toString() }.count() }.toInt()

    @Test
    fun `cleanup deletes nothing when there are fewer than 20 logs even if all are older than 7 days`() = runTest {
        writeLogs("old", 10, Duration.ofDays(8))
        collector().cleanup()
        assertEquals(10, remainingLogs())
    }

    @Test
    fun `cleanup deletes nothing when there are 50 logs and all are newer than 7 days`() = runTest {
        writeLogs("recent", 50, Duration.ofDays(1))
        collector().cleanup()
        assertEquals(50, remainingLogs())
    }

    @Test
    fun `cleanup deletes the oldest 30 of 50 logs when 30 are older than 7 days`() = runTest {
        writeLogs("old", 30, Duration.ofDays(8))
        writeLogs("recent", 20, Duration.ofDays(1))
        collector().cleanup()
        assertEquals(20, remainingLogs())
        assertEquals(20, remainingLogs("recent"))
    }

    @Test
    fun `cleanup deletes the 15 old logs of 50 and keeps the other 35`() = runTest {
        writeLogs("old", 15, Duration.ofDays(8))
        writeLogs("recent", 35, Duration.ofDays(1))
        collector().cleanup()
        assertEquals(35, remainingLogs())
        assertEquals(35, remainingLogs("recent"))
    }

    @Test
    fun `cleanup keeps the newest 20 even when all 25 logs are older than 7 days`() = runTest {
        writeLogs("old", 25, Duration.ofDays(8))
        collector().cleanup()
        assertEquals(20, remainingLogs())
        for (index in 21..25) assertFalse(Files.exists(logs.resolve("coder-ssh-old-$index.log")), "log $index")
    }

    @Test
    fun `cleanup ignores unrelated files and directories`() = runTest {
        writeLogs("old", 25, Duration.ofDays(8))
        val ignored = listOf("12345.log", "other.log", "coder-ssh-old.log.1")
        for (name in ignored) {
            Files.setLastModifiedTime(Files.writeString(logs.resolve(name), "keep"), FileTime.from(Instant.EPOCH))
        }
        val directory = Files.createDirectory(logs.resolve("coder-ssh-directory-1.log"))
        Files.setLastModifiedTime(directory, FileTime.from(Instant.EPOCH))

        collector().cleanup()

        for (name in ignored) assertTrue(Files.exists(logs.resolve(name)))
        assertTrue(Files.isDirectory(directory))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `cleanup skips symlinks and their targets`() = runTest {
        Files.createDirectories(logs)
        val target = Files.writeString(root.resolve("target.log"), "keep")
        Files.setLastModifiedTime(target, FileTime.from(Instant.EPOCH))
        val link = Files.createSymbolicLink(logs.resolve("coder-ssh-link-1.log"), target)
        repeat(20) { Files.writeString(logs.resolve("coder-ssh-$it.log"), "recent") }
        collector().cleanup()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("keep", Files.readString(target))
    }

    @Test
    fun `cancellation propagates without collecting SSH logs`() = runTest {
        Files.createDirectories(logs)
        Files.writeString(logs.resolve("coder-ssh-session-1.log"), "SSH session")
        launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { collector().collect(archive) }
        }.join()
        assertFalse(Files.exists(archive))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `unreadable files do not prevent collecting other logs`() = runTest {
        Files.createDirectories(logs)
        val unreadable = Files.writeString(logs.resolve("coder-ssh-unreadable-1.log"), "unreadable")
        Files.writeString(logs.resolve("coder-ssh-readable-1.log"), "readable")
        val permissions = Files.getPosixFilePermissions(unreadable)
        try {
            Files.setPosixFilePermissions(unreadable, emptySet())
            collector().collect(archive)
            val collected = collected()
            assertEquals("readable", collected["coder-ssh-readable-1.log"])
            if (!Files.isReadable(unreadable)) assertFalse("coder-ssh-unreadable-1.log" in collected)
        } finally {
            Files.setPosixFilePermissions(unreadable, permissions)
        }
    }
}
