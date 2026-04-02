package com.coder.toolbox.cli

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.ex.ResponseException
import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.BINARY_DESTINATION
import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.store.DATA_DIRECTORY
import com.coder.toolbox.store.DISABLE_SIGNATURE_VALIDATION
import com.coder.toolbox.store.ENABLE_BINARY_DIR_FALLBACK
import com.coder.toolbox.store.ENABLE_DOWNLOADS
import com.coder.toolbox.util.ConnectionMonitoringService
import com.coder.toolbox.util.IgnoreOnWindows
import com.coder.toolbox.util.OS
import com.coder.toolbox.util.SemVer
import com.coder.toolbox.util.getOS
import com.coder.toolbox.util.pluginTestSettingsStore
import com.coder.toolbox.util.sha1
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.os.LocalDesktopManager
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.connection.ProxyAuth
import com.jetbrains.toolbox.api.remoteDev.connection.RemoteToolsHelper
import com.jetbrains.toolbox.api.remoteDev.connection.ToolboxProxySettings
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URL
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val noOpTextProgress: (String) -> Unit = { _ -> }

/**
 * Comprehensive tests for [ensureCLI] covering all combinations of
 * binaryDestination (directory / executable / unset) and enableDownloads
 * (true / false).
 *
 * Tests that require executing mock CLI scripts or setWritable(false) are
 * skipped on Windows because:
 * - Mock CLIs are shell/batch scripts saved with the default binary name
 *   (e.g. coder-windows-amd64.exe) and cannot be executed as .exe files.
 * - File.setWritable(false) does not reliably prevent writes on Windows.
 */
internal class EnsureCLITest {
    private val ui = mockk<ToolboxUi>(relaxed = true)
    private val baseContext = CoderToolboxContext(
        ui,
        mockk<EnvironmentUiPageManager>(),
        mockk<EnvironmentStateColorPalette>(),
        mockk<RemoteToolsHelper>(),
        mockk<ClientHelper>(),
        mockk<LocalDesktopManager>(),
        mockk<CoroutineScope>(),
        mockk<Logger>(relaxed = true),
        mockk<LocalizableStringFactory>(relaxed = true),
        CoderSettingsStore(pluginTestSettingsStore(), Environment(), mockk<Logger>(relaxed = true)),
        mockk<CoderSecretsStore>(),
        object : ToolboxProxySettings {
            override fun getProxy(): Proxy? = null
            override fun getProxySelector(): ProxySelector? = null
            override fun getProxyAuth(): ProxyAuth? = null
            override fun addProxyChangeListener(listener: Runnable) {}
            override fun removeProxyChangeListener(listener: Runnable) {}
        },
        mockk<ConnectionMonitoringService>(),
    )

    @BeforeTest
    fun setup() {
        coEvery { ui.showYesNoPopup(any(), any(), any(), any()) } returns true
    }


    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are enabled, returns the CLI when version already matches`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-on-match/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-on-match/data").toString(),
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        // Compute expected path before creating files so isExecutable on the
        // directory does not interfere with the resolution.
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are enabled, downloads and returns a fresh CLI when version mismatches`() {
        val (srv, url) = mockServer(version = "2.0.0")
        try {
            val s = settings(
                BINARY_DESTINATION to testDir("dir-dest-dl-on-mismatch/bin").toString(),
                DATA_DIRECTORY to testDir("dir-dest-dl-on-mismatch/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            val expected = s.binPath(url)
            createBinary(expected, "1.0.0")

            val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            assertEquals(expected, ccm.localBinaryPath)
            assertTrue(ccm.localBinaryPath.toFile().exists())
            assertEquals(SemVer(2, 0, 0), ccm.version())
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `given binaryDestination is a directory and downloads are enabled, propagates non-AccessDenied errors during download to the caller`() {
        val (srv, url) = mockServer(errorCode = HttpURLConnection.HTTP_INTERNAL_ERROR)
        try {
            val s = settings(
                BINARY_DESTINATION to testDir("dir-dest-dl-on-error/bin").toString(),
                DATA_DIRECTORY to testDir("dir-dest-dl-on-error/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            assertFailsWith(ResponseException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are enabled, falls back to the data directory CLI when access is denied and data directory version matches`() {
        val (srv, url) = mockServer(version = "1.9.0")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-on-fallback-match/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-on-fallback-match/data").toString(),
            ENABLE_BINARY_DIR_FALLBACK to "true",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val binParent = s.binPath(url).parent
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "2.0.0")
        try {
            binParent.toFile().mkdirs()
            binParent.toFile().setWritable(false)
            val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            assertEquals(fallbackPath, ccm.localBinaryPath)
            assertEquals(SemVer(2, 0, 0), ccm.version())
        } finally {
            binParent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are enabled, downloads CLI to the data directory when access is denied and data directory CLI is missing`() {
        val (srv, url) = mockServer(version = "1.0.0")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-on-fallback-missing/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-on-fallback-missing/data").toString(),
            ENABLE_BINARY_DIR_FALLBACK to "true",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val binParent = s.binPath(url).parent
        val fallbackPath = s.binPath(url, true)
        try {
            binParent.toFile().mkdirs()
            binParent.toFile().setWritable(false)
            val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            assertEquals(fallbackPath, ccm.localBinaryPath)
            assertTrue(ccm.localBinaryPath.toFile().exists())
            assertEquals(SemVer(1, 0, 0), ccm.version())
        } finally {
            binParent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are enabled, rethrows AccessDeniedException when fallback is enabled but binary path is already inside the data directory`() {
        val (srv, url) = mockServer()
        // Set BINARY_DESTINATION to the same base as DATA_DIRECTORY so that
        // binPath(url).parent == dataDir(url).
        val sharedBase = testDir("dir-dest-dl-on-fallback-same-dir/shared")
        val s = settings(
            BINARY_DESTINATION to sharedBase.toString(),
            DATA_DIRECTORY to sharedBase.toString(),
            ENABLE_BINARY_DIR_FALLBACK to "true",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val binParent = s.binPath(url).parent
        try {
            binParent.toFile().mkdirs()
            binParent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            binParent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are enabled, rethrows AccessDeniedException when binary directory fallback is disabled`() {
        val (srv, url) = mockServer()
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-on-no-fallback/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-on-no-fallback/data").toString(),
            ENABLE_BINARY_DIR_FALLBACK to "false",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val binParent = s.binPath(url).parent
        try {
            binParent.toFile().mkdirs()
            binParent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            binParent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are enabled, rethrows AccessDeniedException when fallback is disabled and binary path is inside the data directory`() {
        val (srv, url) = mockServer()
        val sharedBase = testDir("dir-dest-dl-on-no-fallback-same-dir/shared")
        val s = settings(
            BINARY_DESTINATION to sharedBase.toString(),
            DATA_DIRECTORY to sharedBase.toString(),
            ENABLE_BINARY_DIR_FALLBACK to "false",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val binParent = s.binPath(url).parent
        try {
            binParent.toFile().mkdirs()
            binParent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            binParent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, returns the CLI when version already matches`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-on-match/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-on-match/data").toString(),
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val expected = s.binPath(url)
        assertEquals(binaryFile.toAbsolutePath(), expected)

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, downloads and returns a fresh CLI when version mismatches`() {
        val (srv, url) = mockServer(version = "2.0.0")
        try {
            val binaryFile = testDir("exec-dest-dl-on-mismatch/bin/my-coder")
            createBinary(binaryFile, "1.0.0")
            val s = settings(
                BINARY_DESTINATION to binaryFile.toString(),
                DATA_DIRECTORY to testDir("exec-dest-dl-on-mismatch/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            val expected = s.binPath(url)
            val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            assertEquals(expected, ccm.localBinaryPath)
            assertTrue(ccm.localBinaryPath.toFile().exists())
            assertEquals(SemVer(2, 0, 0), ccm.version())
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, propagates non-AccessDenied errors during download to the caller`() {
        val (srv, url) = mockServer(errorCode = HttpURLConnection.HTTP_INTERNAL_ERROR)
        try {
            val binaryFile = testDir("exec-dest-dl-on-error/bin/my-coder")
            createBinary(binaryFile, "1.0.0")
            val s = settings(
                BINARY_DESTINATION to binaryFile.toString(),
                DATA_DIRECTORY to testDir("exec-dest-dl-on-error/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            assertFailsWith(ResponseException::class) {
                runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            }
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, falls back to the data directory CLI when access is denied and data directory version matches`() {
        val (srv, url) = mockServer(version = "1.9.9")
        val binaryFile = testDir("exec-dest-dl-on-fallback-match/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-on-fallback-match/data").toString(),
            ENABLE_BINARY_DIR_FALLBACK to "true",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "2.0.0")
        try {
            binaryFile.parent.toFile().setWritable(false)
            val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            assertEquals(fallbackPath, ccm.localBinaryPath)
            assertEquals(SemVer(2, 0, 0), ccm.version())
        } finally {
            binaryFile.parent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, downloads CLI to the data directory when access is denied and data directory CLI is missing`() {
        val (srv, url) = mockServer(version = "2.0.0")
        val binaryFile = testDir("exec-dest-dl-on-fallback-missing/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-on-fallback-missing/data").toString(),
            ENABLE_BINARY_DIR_FALLBACK to "true",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val fallbackPath = s.binPath(url, true)
        try {
            binaryFile.parent.toFile().setWritable(false)
            val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            assertEquals(fallbackPath, ccm.localBinaryPath)
            assertTrue(ccm.localBinaryPath.toFile().exists())
            assertEquals(SemVer(2, 0, 0), ccm.version())
        } finally {
            binaryFile.parent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, rethrows AccessDeniedException when fallback is enabled but binary path is already inside the data directory`() {
        val (srv, url) = mockServer()
        // Place the executable inside the data dir so binPath.parent == dataDir.
        val dataBase = testDir("exec-dest-dl-on-fallback-same-dir/data")
        val dataDirForUrl = dataBase.resolve(hostDir(url))
        val binaryFile = dataDirForUrl.resolve("my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to dataBase.toString(),
            ENABLE_BINARY_DIR_FALLBACK to "true",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        // Verify the precondition: binPath.parent == dataDir
        assertEquals(s.dataDir(url), s.binPath(url).parent)
        try {
            binaryFile.parent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            }
        } finally {
            binaryFile.parent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, rethrows AccessDeniedException when binary directory fallback is disabled`() {
        val (srv, url) = mockServer()
        val binaryFile = testDir("exec-dest-dl-on-no-fallback/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-on-no-fallback/data").toString(),
            ENABLE_BINARY_DIR_FALLBACK to "false",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        try {
            binaryFile.parent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            }
        } finally {
            binaryFile.parent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are enabled, rethrows AccessDeniedException when fallback is disabled and binary path is inside the data directory`() {
        val (srv, url) = mockServer()
        val dataBase = testDir("exec-dest-dl-on-no-fallback-same-dir/data")
        val dataDirForUrl = dataBase.resolve(hostDir(url))
        val binaryFile = dataDirForUrl.resolve("my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to dataBase.toString(),
            ENABLE_BINARY_DIR_FALLBACK to "false",
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        assertEquals(s.dataDir(url), s.binPath(url).parent)
        try {
            binaryFile.parent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            }
        } finally {
            binaryFile.parent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are disabled, returns the CLI at destination when version matches`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-off-match/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-off-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(binaryFile.toAbsolutePath(), ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are disabled, returns the data directory CLI when destination version mismatches but data directory version matches`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-off-mismatch-datacli-match/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-off-mismatch-datacli-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "2.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(2, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are disabled, returns the stale destination CLI when both destination and data directory versions mismatch`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-off-mismatch-datacli-wrong/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-off-mismatch-datacli-wrong/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.5.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(binaryFile.toAbsolutePath(), ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are disabled, returns the stale destination CLI when version mismatches and data directory CLI is missing`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-off-mismatch-datacli-missing/bin/my-coder")
        createBinary(binaryFile, "1.0.0")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-off-mismatch-datacli-missing/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(binaryFile.toAbsolutePath(), ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are disabled, returns the data directory CLI when destination is missing and data directory version matches`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-off-no-cli-datacli-match/bin/my-coder")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-off-no-cli-datacli-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is an existing executable and downloads are disabled, returns the stale data directory CLI when destination is missing and data directory version mismatches`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-off-no-cli-datacli-wrong/bin/my-coder")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-off-no-cli-datacli-wrong/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    fun `given binaryDestination is an existing executable and downloads are disabled, throws when both destination and data directory CLIs are missing`() {
        val url = URL("http://test.coder.invalid")
        val binaryFile = testDir("exec-dest-dl-off-both-missing/bin/my-coder")
        val s = settings(
            BINARY_DESTINATION to binaryFile.toString(),
            DATA_DIRECTORY to testDir("exec-dest-dl-off-both-missing/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        assertFailsWith(IllegalStateException::class) {
            runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a symlink to an existing executable, uses the symlink target directly`() {
        val url = URL("http://test.coder.invalid")

        // Simulate e.g. /usr/local/bin/coder being a symlink created by a package manager.
        val realBinary = testDir("symlink-dest/real/my-coder")
        createBinary(realBinary, "1.0.0")
        val symlinkDest = testDir("symlink-dest/link/my-coder")
        symlinkDest.parent.toFile().mkdirs()
        Files.createSymbolicLink(symlinkDest, realBinary.toAbsolutePath())

        val s = settings(
            BINARY_DESTINATION to symlinkDest.toString(),
            DATA_DIRECTORY to testDir("symlink-dest/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )

        assertEquals(symlinkDest.toAbsolutePath(), s.binPath(url))

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(symlinkDest.toAbsolutePath(), ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are disabled, returns the CLI at destination when version matches`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-off-match/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-off-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are disabled, returns the data directory CLI when destination version mismatches but data directory version matches`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-off-mismatch-datacli-match/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-off-mismatch-datacli-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "2.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(2, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are disabled, returns the stale destination CLI when both destination and data directory versions mismatch`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-off-mismatch-datacli-wrong/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-off-mismatch-datacli-wrong/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.5.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are disabled, returns the stale destination CLI when version mismatches and data directory CLI is missing`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-off-mismatch-datacli-missing/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-off-mismatch-datacli-missing/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are disabled, returns the data directory CLI when destination CLI is missing and data directory version matches`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-off-no-cli-datacli-match/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-off-no-cli-datacli-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are disabled, returns the stale data directory CLI when destination CLI is missing and data directory version mismatches`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-off-no-cli-datacli-wrong/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-off-no-cli-datacli-wrong/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given binaryDestination is a directory and downloads are disabled, throws when both destination and data directory CLIs are missing`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            BINARY_DESTINATION to testDir("dir-dest-dl-off-both-missing/bin").toString(),
            DATA_DIRECTORY to testDir("dir-dest-dl-off-both-missing/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        assertFailsWith(IllegalStateException::class) {
            runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are disabled, returns the CLI when version matches`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-off-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are disabled, returns the CLI when the shared path is overwritten with a matching version`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-off-mismatch-datacli-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "2.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(2, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are disabled, returns the stale CLI when version mismatches confirming CLI and data directory paths are identical`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-off-mismatch/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        // When binaryDestination is not configured, binPath(url) and
        // binPath(url, true) resolve to the same path because the
        // isNullOrBlank() early return in binPath fires before the
        // forceDownloadToData check.  So cli and dataCLI share a binary.
        assertEquals(expected, s.binPath(url, true))
        createBinary(expected, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are disabled, returns the stale CLI when version mismatches and no separate data directory CLI path exists`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-off-mismatch-datacli-missing/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are disabled, returns the CLI when version matches at the shared data directory path`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-off-no-cli-datacli-match/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are disabled, returns the stale CLI when version mismatches at the shared data directory path`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-off-no-cli-datacli-wrong/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        val fallbackPath = s.binPath(url, true)
        createBinary(fallbackPath, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
        assertEquals(fallbackPath, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    fun `given no binaryDestination configured and downloads are disabled, throws when no CLI binary exists`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-off-both-missing/data").toString(),
            ENABLE_DOWNLOADS to "false",
        )
        assertFailsWith(IllegalStateException::class) {
            runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are enabled, returns the CLI when version already matches`() {
        val url = URL("http://test.coder.invalid")
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-on-match/data").toString(),
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val expected = s.binPath(url)
        createBinary(expected, "1.0.0")

        val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
        assertEquals(expected, ccm.localBinaryPath)
        assertEquals(SemVer(1, 0, 0), ccm.version())
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are enabled, downloads and returns a fresh CLI when version mismatches`() {
        val (srv, url) = mockServer(version = "2.0.0")
        try {
            val s = settings(
                DATA_DIRECTORY to testDir("no-dest-dl-on-mismatch/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            val expected = s.binPath(url)
            createBinary(expected, "1.0.0")

            val ccm = runBlocking { ensureCLI(ctx(s), url, "2.0.0", noOpTextProgress) }
            assertEquals(expected, ccm.localBinaryPath)
            assertTrue(ccm.localBinaryPath.toFile().exists())
            assertEquals(SemVer(2, 0, 0), ccm.version())
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `given no binaryDestination configured and downloads are enabled, propagates non-AccessDenied errors during download to the caller`() {
        val (srv, url) = mockServer(errorCode = HttpURLConnection.HTTP_INTERNAL_ERROR)
        try {
            val s = settings(
                DATA_DIRECTORY to testDir("no-dest-dl-on-error/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            assertFailsWith(ResponseException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are enabled, rethrows AccessDeniedException since the binary path is always inside the data directory`() {
        val (srv, url) = mockServer()
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-on-access-denied/data").toString(),
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val binParent = s.binPath(url).parent
        try {
            binParent.toFile().mkdirs()
            binParent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            binParent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    @Test
    fun `given no binaryDestination configured and downloads are enabled, downloads and returns a fresh CLI when none exists yet`() {
        val (srv, url) = mockServer(version = "1.0.0")
        try {
            val s = settings(
                DATA_DIRECTORY to testDir("no-dest-dl-on-no-cli/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            val expected = s.binPath(url)

            val ccm = runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            assertEquals(expected, ccm.localBinaryPath)
            assertTrue(ccm.localBinaryPath.toFile().exists())
            if (getOS() != OS.WINDOWS) {
                assertEquals(SemVer(1, 0, 0), ccm.version())
            }
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `given no binaryDestination configured and downloads are enabled, propagates non-AccessDenied errors during download when CLI is missing`() {
        val (srv, url) = mockServer(errorCode = HttpURLConnection.HTTP_INTERNAL_ERROR)
        try {
            val s = settings(
                DATA_DIRECTORY to testDir("no-dest-dl-on-no-cli-error/data").toString(),
                DISABLE_SIGNATURE_VALIDATION to "true",
            )
            assertFailsWith(ResponseException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `given no binaryDestination configured and downloads are enabled, rethrows AccessDeniedException when CLI is missing and access to the data directory is denied`() {
        val (srv, url) = mockServer()
        val s = settings(
            DATA_DIRECTORY to testDir("no-dest-dl-on-no-cli-access-denied/data").toString(),
            DISABLE_SIGNATURE_VALIDATION to "true",
        )
        val binParent = s.binPath(url).parent
        try {
            binParent.toFile().mkdirs()
            binParent.toFile().setWritable(false)
            assertFailsWith(AccessDeniedException::class) {
                runBlocking { ensureCLI(ctx(s), url, "1.0.0", noOpTextProgress) }
            }
        } finally {
            binParent.toFile().setWritable(true)
            srv.stop(0)
        }
    }

    // Utilities

    private fun testDir(id: String): Path = tmpdir.resolve(id)

    private fun mkbin(str: String): String = if (getOS() == OS.WINDOWS) {
        listOf("@echo off", str)
    } else {
        listOf("#!/bin/sh", str)
    }.joinToString(System.lineSeparator())

    private fun echo(str: String): String = if (getOS() == OS.WINDOWS) "echo $str" else "echo '$str'"

    private fun mkbinVersion(version: String): String = mkbin(echo("""{"version": "$version"}"""))

    private fun settings(vararg pairs: Pair<String, String>): CoderSettingsStore =
        CoderSettingsStore(pluginTestSettingsStore(*pairs), Environment(), baseContext.logger)

    private fun ctx(s: CoderSettingsStore): CoderToolboxContext = baseContext.copy(settingsStore = s)

    private fun createBinary(path: Path, version: String) {
        path.parent.toFile().mkdirs()
        path.toFile().writeText(mkbinVersion(version))
        if (getOS() != OS.WINDOWS) {
            path.toFile().setExecutable(true)
        }
    }

    private fun mockServer(
        errorCode: Int = 0,
        version: String? = null,
    ): Pair<HttpServer, URL> {
        val srv = HttpServer.create(InetSocketAddress(0), 0)
        srv.createContext("/") { exchange ->
            var code = HttpURLConnection.HTTP_OK
            var response = mkbinVersion(version ?: "${srv.address.port}.0.0")
            if (exchange.requestURI.path.contains(".asc")) {
                code = HttpURLConnection.HTTP_NOT_FOUND
                response = "not found"
            } else if (!exchange.requestURI.path.startsWith("/bin/coder-")) {
                code = HttpURLConnection.HTTP_NOT_FOUND
                response = "not found"
            } else if (errorCode != 0) {
                code = errorCode
                response = "error code $code"
            } else {
                val eTags = exchange.requestHeaders["If-None-Match"]
                if (eTags != null && eTags.contains("\"${sha1(response.byteInputStream())}\"")) {
                    code = HttpURLConnection.HTTP_NOT_MODIFIED
                    response = "not modified"
                }
            }
            val body = response.toByteArray()
            exchange.responseHeaders["Content-Type"] = "application/octet-stream"
            exchange.sendResponseHeaders(code, if (code == HttpURLConnection.HTTP_OK) body.size.toLong() else -1)
            exchange.responseBody.write(body)
            exchange.close()
        }
        srv.start()
        return Pair(srv, URL("http://localhost:" + srv.address.port))
    }

    /** Build the host directory component used by [CoderSettingsStore.withHost]. */
    private fun hostDir(url: URL): String =
        if (url.port > 0) "${url.host}-${url.port}" else url.host

    companion object {
        private val tmpdir: Path = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("coder-toolbox-test").resolve("ensure-cli")

        @JvmStatic
        @BeforeAll
        fun cleanup() {
            tmpdir.toFile().deleteRecursively()
        }
    }
}
