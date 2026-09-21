package com.coder.toolbox.diagnostics

import com.coder.toolbox.cli.WorkspaceAddress
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.sdk.v2.models.Workspace
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS.LINUX
import org.junit.jupiter.api.condition.OS.MAC
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderProviderLogCollectorTest {
    @TempDir
    lateinit var root: Path
    private val logger = mockk<CoderLogger>(relaxed = true)

    private fun collector(
        workspaces: suspend () -> List<Workspace> = { emptyList() },
        logs: Map<String, Path> = emptyMap(),
        bundle: suspend (WorkspaceAddress, Path) -> Unit = { _, path -> Files.writeString(path, "bundle"); Unit },
    ) = CoderProviderLogCollector(
        logger, workspaces, bundle, { logs }, { Files.createDirectory(root.resolve("output")) }
    )

    private fun contents(archive: Path): Map<String, String> = ZipFile(archive.toFile()).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }

    @Test
    fun `archive contains every workspace and agent plus recursive Toolbox and daemon logs`() = runTest {
        val running = DataGen.workspace(
            "running", agents = mapOf(
                "first" to UUID.randomUUID().toString(), "second" to UUID.randomUUID().toString(),
            )
        )
        val agentless = DataGen.workspace("agentless")
        val toolbox = Files.createDirectories(root.resolve("toolbox/plugin/subdirectory"))
        Files.writeString(toolbox.resolve("trace.log.1"), "rotated plugin log")
        Files.writeString(root.resolve("toolbox/toolbox.log"), "toolbox log")
        val daemon = Files.createDirectories(root.resolve("daemon"))
        Files.writeString(daemon.resolve("daemon.log"), "daemon log")
        val calls = mutableListOf<String>()
        val archive = collector(
            { listOf(running, agentless, running) }, mapOf(
                "toolbox" to root.resolve("toolbox"), "daemon" to daemon,
            )
        ) { address, path ->
            calls.add("${address.ownerAndWsName}:${address.agentName}")
            Files.writeString(path, "deployment bundle")
        }.collect {}
        val files = contents(archive)
        assertEquals(listOf("owner/running:first", "owner/running:second", "owner/agentless:null"), calls)
        assertEquals(3, files.keys.count { it.endsWith("coder-support.zip") })
        assertEquals("rotated plugin log", files["toolbox/plugin/subdirectory/trace.log.1"])
        assertEquals("toolbox log", files["toolbox/toolbox.log"])
        assertEquals("daemon log", files["daemon/daemon.log"])
        assertFalse(files.containsKey("collection-report.txt"))
        assertFalse(Files.exists(archive.parent.resolve("contents")))
        assertTrue(files.keys.all { !it.contains('\\') && !it.startsWith('/') })
    }

    @Test
    fun `failed workspace bundle does not discard subsequent bundles or local logs`() = runTest {
        val logs = Files.createDirectories(root.resolve("logs"))
        Files.writeString(logs.resolve("toolbox.log"), "local logs")
        val archive = collector(
            { listOf(DataGen.workspace("failed"), DataGen.workspace("healthy")) },
            mapOf("toolbox" to logs)
        ) { address, path ->
            Files.writeString(path, "partial or complete")
            if (address.wsName == "failed") error("sensitive response")
        }.collect {}
        val files = contents(archive)
        assertEquals(1, files.keys.count { it.endsWith("coder-support.zip") })
        assertEquals(1, files.keys.count { it.endsWith("coder-support-error.txt") })
        assertEquals("local logs", files["toolbox/toolbox.log"])
        assertTrue(files.getValue("collection-report.txt").contains("owner/failed"))
        assertFalse(files.values.any { it.contains("sensitive response") })
    }

    @Test
    fun `workspace listing failure and missing daemon logs still preserve Toolbox logs`() = runTest {
        val logs = Files.createDirectories(root.resolve("logs"))
        Files.writeString(logs.resolve("toolbox.log"), "local logs")
        val archive = collector(
            { error("unavailable") }, mapOf("toolbox" to logs, "daemon" to root.resolve("missing"))
        ).collect {}
        val files = contents(archive)
        assertEquals("local logs", files["toolbox/toolbox.log"])
        assertTrue(files.getValue("collection-report.txt").contains("Could not list Coder workspaces"))
        assertTrue(files.getValue("collection-report.txt").contains("Log directory is unavailable"))
    }

    @Test
    fun `cancelling an active bundle removes all temporary files`() = runTest {
        val started = CompletableDeferred<Unit>()
        val collection = async {
            collector({ listOf(DataGen.workspace("ws")) }) { _, path ->
                Files.writeString(path, "partial")
                started.complete(Unit)
                awaitCancellation()
            }.collect {}
        }
        started.await()
        collection.cancelAndJoin()
        assertFalse(Files.exists(root.resolve("output")))
    }

    @Test
    fun `cancellation before archive creation removes staged diagnostics`() = runTest {
        assertFailsWith<CancellationException> {
            collector().collect { if (it.message == "Creating log archive…") throw CancellationException() }
        }
        assertFalse(Files.exists(root.resolve("output")))
    }

    @Test
    @EnabledOnOs(MAC, LINUX)
    fun `symlinks do not pull unrelated files into the archive`() = runTest {
        val logs = Files.createDirectory(root.resolve("logs"))
        val outside = Files.writeString(root.resolve("outside"), "not a log")
        Files.createSymbolicLink(logs.resolve("linked.log"), outside)
        Files.createSymbolicLink(logs.resolve("loop"), logs)
        Files.writeString(logs.resolve("actual.log"), "log")
        val files = contents(collector(logs = mapOf("toolbox" to logs)).collect {})
        assertEquals(mapOf("toolbox/actual.log" to "log"), files)
    }

    @Test
    fun `log source containing the temporary directory does not recursively collect itself`() = runTest {
        Files.writeString(root.resolve("actual.log"), "log")
        val files = contents(collector(logs = mapOf("toolbox" to root)).collect {})
        assertEquals(mapOf("toolbox/actual.log" to "log"), files)
    }

    @Test
    fun `external log paths containing shell syntax are read as literal directories`() = runTest {
        val literalName = "logs ; echo injected & \$(echo injected) `echo injected` %TEMP% 'quoted'"
        val toolbox = Files.createDirectories(root.resolve("property").resolve(literalName))
        val daemon = Files.createDirectories(root.resolve("environment").resolve(literalName))
        Files.writeString(toolbox.resolve("toolbox.log"), "Toolbox log")
        Files.writeString(daemon.resolve("daemon.log"), "Daemon log")
        val directories = ToolboxLogPaths(
            property = { if (it == "toolbox.log.path") toolbox.toString() else null },
            environment = { if (it == "JETBRAINS_DAEMON_LOG_DIRECTORY") daemon.toString() else null },
        ).directories()

        val files = contents(collector(logs = directories).collect {})

        assertEquals(mapOf("toolbox/toolbox.log" to "Toolbox log", "daemon/daemon.log" to "Daemon log"), files)
    }
}
