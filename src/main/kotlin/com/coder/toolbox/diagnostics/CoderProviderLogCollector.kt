package com.coder.toolbox.diagnostics

import com.coder.toolbox.cli.WorkspaceAddress
import com.coder.toolbox.sdk.v2.models.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext

/** Collects local Toolbox logs and Coder support bundles for all accessible workspaces. */
internal class CoderProviderLogCollector(
    private val logger: CoderLogger,
    private val workspaces: suspend () -> List<Workspace>,
    private val collectBundle: suspend (WorkspaceAddress, Path) -> Unit,
    private val logDirectories: () -> Map<String, Path> = { ToolboxLogPaths().directories() },
    private val createTempDirectory: (String) -> Path = { Files.createTempDirectory(it) },
) {
    suspend fun collect(progress: (LogCollectionProgress) -> Unit): Path = withContext(Dispatchers.IO) {
        val directory = createTempDirectory("coder-toolbox-logs-")
        val staging = directory.resolve("contents")
        val archive = directory.resolve("coder-toolbox-logs.zip")
        var completed = false
        try {
            Files.createDirectories(staging)
            val reports = mutableListOf<String>()
            collectWorkspaces(staging, reports, progress)
            progress(LogCollectionProgress("Collecting Toolbox and JetBrains daemon logs…"))
            logDirectories().forEach { (name, source) ->
                copyLogs(source, staging.resolve(name), directory, reports)
            }
            if (reports.isNotEmpty()) {
                Files.writeString(staging.resolve("collection-report.txt"), reports.joinToString("\n", postfix = "\n"))
            }
            progress(LogCollectionProgress("Creating log archive…"))
            zip(staging, archive)
            currentCoroutineContext().ensureActive()
            completed = true
            archive
        } finally {
            deleteTree(if (completed) staging else directory)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun collectWorkspaces(
        root: Path,
        reports: MutableList<String>,
        progress: (LogCollectionProgress) -> Unit,
    ) {
        progress(LogCollectionProgress("Listing Coder workspaces…"))
        val allWorkspaces = try {
            workspaces().distinctBy { it.id }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(ex, "Could not list workspaces for log collection")
            reports.add("Could not list Coder workspaces (${ex.javaClass.simpleName}). Local logs are included.")
            return
        }
        allWorkspaces.forEachIndexed { index, workspace ->
            val agents = workspace.latestBuild.resources.flatMap { it.agents.orEmpty() }.distinctBy { it.id }
            val targets = agents.ifEmpty { listOf(null) }
            for (agent in targets) {
                currentCoroutineContext().ensureActive()
                val address = WorkspaceAddress.from(workspace, agent)
                val name = address.ownerAndWsName + (address.agentName?.let { ".$it" } ?: "")
                progress(
                    LogCollectionProgress(
                        "Collecting workspace diagnostics…", "${index + 1}/${allWorkspaces.size}: $name"
                    )
                )
                val destination = root.resolve("workspaces").resolve(workspace.id.toString())
                    .resolve(agent?.id?.toString() ?: "workspace")
                Files.createDirectories(destination)
                Files.writeString(destination.resolve("workspace.txt"), "$name\n")
                CoderSupportBundleCollector(logger) { collectBundle(address, it) }
                    .collectAdditionalDiagnostics(destination)
                if (!Files.isRegularFile(destination.resolve("coder-support.zip"))) {
                    reports.add("Could not collect $name. See its coder-support-error.txt for details.")
                }
            }
        }
    }

    private suspend fun copyLogs(
        source: Path,
        destination: Path,
        temporaryDirectory: Path,
        reports: MutableList<String>,
    ) {
        val coroutineContext = currentCoroutineContext()
        val excluded = temporaryDirectory.toAbsolutePath().normalize()
        if (!Files.exists(source)) {
            reports.add("Log directory is unavailable: $source")
            return
        }
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutineContext.ensureActive()
                if (dir.toAbsolutePath().normalize().startsWith(excluded)) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(destination.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutineContext.ensureActive()
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val target = destination.resolve(source.relativize(file))
                try {
                    // Snapshot the current size so an actively growing log cannot keep collection running.
                    Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                        Files.newOutputStream(target).use { output ->
                            copy(input, output, coroutineContext, attrs.size())
                        }
                    }
                } catch (ex: IOException) {
                    Files.deleteIfExists(target)
                    reports.add("Could not copy $file (${ex.javaClass.simpleName}).")
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                coroutineContext.ensureActive()
                reports.add("Could not read $file (${exc.javaClass.simpleName}).")
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) reports.add("Could not finish reading $dir (${exc.javaClass.simpleName}).")
                return FileVisitResult.CONTINUE
            }
        })
    }

    private suspend fun zip(source: Path, archive: Path) {
        val coroutineContext = currentCoroutineContext()
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            Files.walk(source).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach { file ->
                    coroutineContext.ensureActive()
                    val name = source.relativize(file).joinToString("/") { it.toString() }
                    output.putNextEntry(ZipEntry(name))
                    copyFile(file, output, coroutineContext)
                    output.closeEntry()
                }
            }
        }
    }

    private fun copyFile(file: Path, output: OutputStream, context: CoroutineContext) {
        Files.newInputStream(file).use { input -> copy(input, output, context) }
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        context: CoroutineContext,
        limit: Long = Long.MAX_VALUE,
    ) {
        var remaining = limit
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            context.ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun deleteTree(root: Path) {
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }.onFailure { logger.warn(it, "Could not remove temporary diagnostic files") }
    }
}

internal data class LogCollectionProgress(val message: String, val detail: String = "")
