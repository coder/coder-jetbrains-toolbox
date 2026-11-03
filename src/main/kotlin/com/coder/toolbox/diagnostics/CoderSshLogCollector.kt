package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant

// Only Coder SSH logs named coder-ssh-<timestamp>-<pid>.log.
private val SSH_LOG_FILE = Regex("coder-ssh.*-\\d+\\.log")
private const val SSH_LOG_MAX_AGE_3_DAYS_SECONDS = 3L * 24 * 60 * 60
private const val SSH_LOG_CLEANUP_AGE_7_DAYS_SECONDS = 7L * 24 * 60 * 60
private const val SSH_LOG_KEEP_COUNT = 20
private const val SSH_LOG_MAX_50_MB_BYTES = 50L * 1024 * 1024
private const val ARCHIVE_DIRECTORY = "toolbox-logs/ssh"

internal class CoderSshLogCollector(
    private val logger: CoderLogger,
    private val logDirectory: Path,
) {
    /**
     * Adds recent SSH logs, and the log of the active SSH process, to [archive] in place.
     * Each log is cut to its last 50 MB.
     */
    suspend fun collect(
        archive: Path,
        sessionId: SessionId? = null,
        activeSshPid: Long? = null,
    ) = withContext(Dispatchers.IO) {
        if (Files.notExists(logDirectory)) return@withContext

        val cutoff = Instant.now().minusSeconds(SSH_LOG_MAX_AGE_3_DAYS_SECONDS)
        ZipFile(archive.toFile()).use { zip ->
            for (entry in sshLogFiles()) {
                ensureActive()
                val attributes = readAttributes(entry, sessionId) ?: continue
                val isActive = activeSshPid != null && entry.fileName.toString().endsWith("-$activeSshPid.log")
                val isRecent = !attributes.lastModifiedTime().toInstant().isBefore(cutoff)
                if (isActive || isRecent) addToArchive(zip, entry, attributes.lastModifiedTime(), sessionId)
            }
        }
    }

    /** Delete logs that are both outside the newest 20 and older than 7 days. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun cleanup(sessionId: SessionId? = null) = withContext(Dispatchers.IO) {
        try {
            if (Files.notExists(logDirectory)) return@withContext
            val cutoff = Instant.now().minusSeconds(SSH_LOG_CLEANUP_AGE_7_DAYS_SECONDS)
            sshLogFiles()
                .mapNotNull { entry ->
                    ensureActive()
                    readAttributes(entry, sessionId)?.let { entry to it.lastModifiedTime().toInstant() }
                }
                .sortedByDescending { (_, modified) -> modified }
                .drop(SSH_LOG_KEEP_COUNT)
                .filter { (_, modified) -> modified.isBefore(cutoff) }
                .forEach { (path, _) ->
                    ensureActive()
                    runCatching { Files.deleteIfExists(path) }
                        .onFailure { logger.warn(sessionId, it, "Could not delete old SSH log $path") }
                }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(sessionId, ex, "Could not clean up SSH logs in $logDirectory")
        }
    }

    // The log directory can be shared, so filter by name before looking at anything else.
    private fun sshLogFiles(): List<Path> = Files.newDirectoryStream(logDirectory) {
        SSH_LOG_FILE.matches(it.fileName.toString())
    }.use { it.toList() }

    @Suppress("TooGenericExceptionCaught")
    private fun readAttributes(source: Path, sessionId: SessionId?): BasicFileAttributes? {
        return try {
            Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS).takeIf { it.isRegularFile }
        } catch (ex: Exception) {
            logger.warn(sessionId, ex, "Could not inspect SSH log $source")
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun addToArchive(zip: ZipFile, source: Path, modified: FileTime, sessionId: SessionId?) {
        try {
            FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
                val size = input.size()
                if (size > SSH_LOG_MAX_50_MB_BYTES) {
                    logger.warn(
                        sessionId,
                        "Truncating SSH log $source ($size bytes) to its last $SSH_LOG_MAX_50_MB_BYTES bytes",
                    )
                    input.position(size - SSH_LOG_MAX_50_MB_BYTES)
                }
                val parameters = ZipParameters().apply {
                    fileNameInZip = "$ARCHIVE_DIRECTORY/${source.fileName}"
                    lastModifiedFileTime = modified.toMillis()
                }
                zip.addStream(Channels.newInputStream(input), parameters)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(sessionId, ex, "Could not collect SSH log $source")
        }
    }
}
