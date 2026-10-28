package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
import com.coder.toolbox.settings.ReadOnlyCoderSettings
import com.jetbrains.toolbox.api.remoteDev.deploy.DiagnosticInfoCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.nio.file.Files
import java.nio.file.Path

/** Collects a workspace support bundle, a masked settings snapshot, and recent local Coder SSH logs. */
internal class CoderSupportBundleCollector(
    private val logger: CoderLogger,
    sshLogDirectory: Path,
    private val settings: ReadOnlyCoderSettings,
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
            logger.flush("support_bundle", sessionId)
            Files.createDirectories(logsRootFolder)
            collectSupportBundle(logsRootFolder, sessionId)
            // Without a usable support bundle the local diagnostics still go into an archive of their own.
            val bundle = logsRootFolder.resolve("coder-support.zip")
            val hasBundle = ZipFile(bundle.toFile()).isValidZipFile
            val archive = if (hasBundle) bundle else logsRootFolder.resolve("coder-ssh.zip")
            collectSettings(archive, sessionId)
            sshLogs.collect(archive, sessionId, sshPid)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(sessionId, ex, "Could not collect Coder workspace diagnostics")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectSettings(archive: Path, sessionId: SessionId?) {
        try {
            val snapshot = collectSettingsSnapshot(settings)
            ZipFile(archive.toFile()).use { zip ->
                snapshot.inputStream().use { input ->
                    zip.addStream(input, ZipParameters().apply { fileNameInZip = "toolbox-logs/settings.json" })
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.error(
                sessionId,
                ex,
                "Could not collect Coder Toolbox settings; diagnostic collection will continue without the settings",
            )
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
