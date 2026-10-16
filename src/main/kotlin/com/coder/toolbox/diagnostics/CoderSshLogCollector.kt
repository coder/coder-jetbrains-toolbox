package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

// Include current CLI session filenames and older PID-based logs.
private val SSH_LOG_FILE = Regex("coder-ssh.*\\.log(?:\\.\\d+)?|\\d+\\.log")
private const val SSH_LOG_MAX_AGE_SECONDS = 3L * 24 * 60 * 60
private const val SSH_LOG_MAX_BYTES = 50L * 1024 * 1024

internal class CoderSshLogCollector(
    private val logger: CoderLogger,
    private val logDirectory: Path,
) {
    suspend fun collect(
        logsRootFolder: Path,
        sessionId: SessionId? = null,
        activeSshPid: Long? = null,
    ) = withContext(Dispatchers.IO) {
        // Older CLIs may not have created any session logs.
        if (Files.notExists(logDirectory)) return@withContext

        val cutoff = Instant.now().minusSeconds(SSH_LOG_MAX_AGE_SECONDS)
        val entries = Files.newDirectoryStream(logDirectory).use { it.toList() }
        // CLI proxy log names end in the parent SSH PID; timestamps distinguish reused PIDs.
        val activeLog = activeSshPid?.let { pid ->
            entries.filter { entry ->
                val name = entry.fileName.toString()
                (name == "$pid.log" || (name.startsWith("coder-ssh") && name.endsWith("-$pid.log"))) &&
                        Files.isRegularFile(entry, NOFOLLOW_LINKS)
            }.maxByOrNull { it.fileName.toString() }
        }
        for (entry in entries) {
            ensureActive()
            // An active session can remain quiet longer than the collection window.
            collectFile(entry, logsRootFolder.resolve("coder-ssh"), cutoff.takeUnless { entry == activeLog }, sessionId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun CoroutineScope.collectFile(source: Path, destination: Path, cutoff: Instant?, sessionId: SessionId?) {
        if (!SSH_LOG_FILE.matches(source.fileName.toString())) return
        var output: Path? = null
        try {
            val attributes = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val expired = cutoff != null && attributes.lastModifiedTime().toInstant().isBefore(cutoff)
            if (!attributes.isRegularFile || expired) return

            FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
                val size = input.size()
                if (size > SSH_LOG_MAX_BYTES) {
                    logger.warn(
                        sessionId,
                        "Truncating SSH log $source ($size bytes) to its last $SSH_LOG_MAX_BYTES bytes",
                    )
                }
                Files.createDirectories(destination)
                val targetPath = destination.resolve(source.fileName)
                output = targetPath
                FileChannel.open(targetPath, CREATE, WRITE, TRUNCATE_EXISTING).use { target ->
                    copyTail(input, target, size)
                }
            }
        } catch (ex: CancellationException) {
            output?.let { runCatching { Files.deleteIfExists(it) } }
            throw ex
        } catch (ex: Exception) {
            output?.let { runCatching { Files.deleteIfExists(it) } }
            logger.warn(sessionId, ex, "Could not collect SSH log $source")
        }
    }

    private fun CoroutineScope.copyTail(input: FileChannel, target: FileChannel, size: Long) {
        var remaining = minOf(size, SSH_LOG_MAX_BYTES)
        var offset = size - remaining
        while (remaining > 0) {
            ensureActive()
            val copied = input.transferTo(offset, remaining, target)
            if (copied == 0L) break
            offset += copied
            remaining -= copied
        }
    }
}
