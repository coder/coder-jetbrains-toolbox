package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.jetbrains.toolbox.api.remoteDev.deploy.DiagnosticInfoCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.nio.file.Files
import java.nio.file.Path

/** Collects a workspace support bundle and recent local Coder SSH logs. */
internal class CoderSupportBundleCollector(
    private val logger: CoderLogger,
    sshLogDirectory: Path,
    private val currentSessionId: () -> SessionId? = { null },
    private val sshPid: Long? = null,
    private val collectBundle: suspend (Path) -> Unit,
) : DiagnosticInfoCollector {
    private val sshLogs = CoderSshLogCollector(logger, sshLogDirectory)

    // Toolbox discards the additional-diagnostics directory if the collector throws.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun collectAdditionalDiagnostics(logsRootFolder: Path) = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId()
        try {
            Files.createDirectories(logsRootFolder)
            collectSupportBundle(logsRootFolder, sessionId)
            // Without a usable support bundle the SSH logs still go into an archive of their own.
            val bundle = logsRootFolder.resolve("coder-support.zip")
            val hasBundle = ZipFile(bundle.toFile()).isValidZipFile
            val archive = if (hasBundle) bundle else logsRootFolder.resolve("coder-ssh.zip")
            sshLogs.collect(archive, sessionId, sshPid)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(sessionId, ex, "Could not collect Coder workspace diagnostics")
        }
    }

    // A failed bundle is replaced by an error report so the SSH logs are still collected.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun collectSupportBundle(logsRootFolder: Path, sessionId: SessionId?) {
        val bundle = logsRootFolder.resolve("coder-support.zip")
        try {
            collectBundle(bundle)
        } catch (ex: CancellationException) {
            runCatching { Files.deleteIfExists(bundle) }
            throw ex
        } catch (ex: Exception) {
            runCatching { Files.deleteIfExists(bundle) }
            logger.warn(sessionId, ex, "Could not collect the Coder support bundle")
            runCatching {
                Files.writeString(
                    logsRootFolder.resolve("coder-support-error.txt"),
                    "Coder support bundle collection failed (${ex.javaClass.simpleName}). " +
                            "Check deployment connectivity, login, and CLI support for 'coder support bundle' " +
                            "(Coder 2.10 or newer). Other Toolbox logs are still available.\n",
                )
            }.onFailure { logger.warn(sessionId, it, "Could not write the Coder diagnostic failure report") }
        }
    }
}
