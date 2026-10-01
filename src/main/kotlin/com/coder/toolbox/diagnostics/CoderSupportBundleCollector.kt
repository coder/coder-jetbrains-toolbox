package com.coder.toolbox.diagnostics

import com.jetbrains.toolbox.api.remoteDev.deploy.DiagnosticInfoCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Collects a Coder support bundle for a workspace and its optional agent. */
internal class CoderSupportBundleCollector(
    private val logger: CoderLogger,
    private val collectBundle: suspend (Path) -> Unit,
) : DiagnosticInfoCollector {
    // Toolbox discards the additional-diagnostics directory if the collector throws.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun collectAdditionalDiagnostics(logsRootFolder: Path) = withContext(Dispatchers.IO) {
        val bundle = logsRootFolder.resolve("coder-support.zip")
        try {
            Files.createDirectories(logsRootFolder)
            collectBundle(bundle)
        } catch (ex: CancellationException) {
            runCatching { Files.deleteIfExists(bundle) }
            throw ex
        } catch (ex: Exception) {
            logger.warn(ex, "Could not collect the Coder support bundle")
            runCatching {
                Files.deleteIfExists(bundle)
                Files.writeString(
                    logsRootFolder.resolve("coder-support-error.txt"),
                    "Coder support bundle collection failed (${ex.javaClass.simpleName}). " +
                            "Check deployment connectivity, login, and CLI support for 'coder support bundle' " +
                            "(Coder 2.10 or newer). Other Toolbox logs are still available.\n",
                )
            }.onFailure { logger.warn(it, "Could not write the Coder diagnostic failure report") }
        }
        Unit
    }
}
