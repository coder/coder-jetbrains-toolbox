package com.coder.toolbox.store

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.util.pluginTestSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class CoderSettingsStoreTest {
    private var originalOsName: String? = null
    private var originalOsArch: String? = null

    private lateinit var store: CoderSettingsStore

    @BeforeTest
    fun setUp() {
        originalOsName = System.getProperty("os.name")
        originalOsArch = System.getProperty("os.arch")

        store = CoderSettingsStore(
            pluginTestSettingsStore(),
            Environment(),
            mockk<Logger>(relaxed = true)
        )
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("os.name", originalOsName)
        System.setProperty("os.arch", originalOsArch)
    }

    @Test
    fun `Default CLI and signature for Windows AMD64`() =
        assertBinaryAndSignature("Windows 10", "amd64", "coder-windows-amd64.exe", "coder-windows-amd64.exe.asc")

    @Test
    fun `Default CLI and signature for Windows ARM64`() =
        assertBinaryAndSignature("Windows 10", "aarch64", "coder-windows-arm64.exe", "coder-windows-arm64.exe.asc")

    @Test
    fun `Default CLI and signature for Linux AMD64`() =
        assertBinaryAndSignature("Linux", "x86_64", "coder-linux-amd64", "coder-linux-amd64.asc")

    @Test
    fun `Default CLI and signature for Linux ARM64`() =
        assertBinaryAndSignature("Linux", "aarch64", "coder-linux-arm64", "coder-linux-arm64.asc")

    @Test
    fun `Default CLI and signature for Linux ARMV7`() =
        assertBinaryAndSignature("Linux", "armv7l", "coder-linux-armv7", "coder-linux-armv7.asc")

    @Test
    fun `Default CLI and signature for Mac AMD64`() =
        assertBinaryAndSignature("Mac OS X", "x86_64", "coder-darwin-amd64", "coder-darwin-amd64.asc")

    @Test
    fun `Default CLI and signature for Mac ARM64`() =
        assertBinaryAndSignature("Mac OS X", "aarch64", "coder-darwin-arm64", "coder-darwin-arm64.asc")

    @Test
    fun `Default CLI and signature for unknown OS and Arch`() =
        assertBinaryAndSignature(null, null, "coder-windows-amd64.exe", "coder-windows-amd64.exe.asc")

    @Test
    fun `Default CLI and signature for unknown Arch fallback on Linux`() =
        assertBinaryAndSignature("Linux", "mips64", "coder-linux-amd64", "coder-linux-amd64.asc")

    // --- binPath tests ---
    @Test
    fun `binPath uses dataDir when binaryDestination is null`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith()
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)

        assertTrue(result.isAbsolute)
        assertTrue(result.toString().endsWith("/test.coder.com/coder-linux-amd64"))
    }

    @Test
    fun `binPath uses dataDir when binaryDestination is empty`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith(BINARY_DESTINATION to "")
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)

        assertTrue(result.isAbsolute)
        assertTrue(result.toString().endsWith("/test.coder.com/coder-linux-amd64"))
    }

    @Test
    fun `binPath uses dataDir when forceDownloadToData is true even with binaryDestination set`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith(BINARY_DESTINATION to "/custom/path")
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url, forceDownloadToData = true)

        assertTrue(result.isAbsolute)
        assertTrue(result.toString().endsWith("/test.coder.com/coder-linux-amd64"))
        assertTrue(!result.toString().contains("/custom/path"))
    }

    @Test
    fun `binPath returns absolute binaryDestination path when downloads are disabled`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith(
            BINARY_DESTINATION to "/usr/local/bin/coder",
            ENABLE_DOWNLOADS to "false"
        )
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)

        assertEquals("/usr/local/bin/coder", result.toString())
    }

    @Test
    fun `binPath expands tilde in binaryDestination when downloads are disabled`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith(
            BINARY_DESTINATION to "~/bin/coder",
            ENABLE_DOWNLOADS to "false"
        )
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)
        val home = System.getProperty("user.home")

        assertTrue(result.isAbsolute)
        assertEquals("$home/bin/coder", result.toString())
    }

    @Test
    fun `binPath expands HOME in binaryDestination when downloads are disabled`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith(
            BINARY_DESTINATION to "\$HOME/bin/coder",
            ENABLE_DOWNLOADS to "false"
        )
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)
        val home = System.getProperty("user.home")

        assertTrue(result.isAbsolute)
        assertEquals("$home/bin/coder", result.toString())
    }

    @Test
    fun `binPath uses binaryDestination as base dir with host subdirectory when downloads are enabled`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith(BINARY_DESTINATION to "/opt/coder-cli")
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)

        assertEquals("/opt/coder-cli/test.coder.com/coder-linux-amd64", result.toString())
    }

    @Test
    fun `binPath includes port in host subdirectory when URL has non-default port`() {
        setOsAndArch("Linux", "x86_64")
        val settings = storeWith(BINARY_DESTINATION to "/opt/coder-cli")
        val url = URL("https://test.coder.com:8443")
        val result = settings.binPath(url)

        assertEquals("/opt/coder-cli/test.coder.com-8443/coder-linux-amd64", result.toString())
    }

    @Test
    fun `binPath uses correct binary name for Windows`() {
        setOsAndArch("Windows 10", "amd64")
        val settings = storeWith(BINARY_DESTINATION to "/opt/coder-cli")
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)

        assertTrue(result.toString().endsWith("coder-windows-amd64.exe"))
    }

    @Test
    fun `binPath uses correct binary name for Mac ARM64`() {
        setOsAndArch("Mac OS X", "aarch64")
        val settings = storeWith(BINARY_DESTINATION to "/opt/coder-cli")
        val url = URL("https://test.coder.com")
        val result = settings.binPath(url)

        assertTrue(result.toString().endsWith("coder-darwin-arm64"))
    }

    private fun assertBinaryAndSignature(
        osName: String?,
        arch: String?,
        expectedBinary: String,
        expectedSignature: String
    ) {
        setOsAndArch(osName, arch)
        assertEquals(expectedBinary, store.defaultCliBinaryNameByOsAndArch)
        assertEquals(expectedSignature, store.defaultSignatureNameByOsAndArch)
    }

    private fun setOsAndArch(osName: String?, arch: String?) {
        if (osName == null) System.clearProperty("os.name") else System.setProperty("os.name", osName)
        if (arch == null) System.clearProperty("os.arch") else System.setProperty("os.arch", arch)
    }

    private fun storeWith(vararg pairs: Pair<String, String>): CoderSettingsStore =
        CoderSettingsStore(
            pluginTestSettingsStore(*pairs),
            Environment(),
            mockk<Logger>(relaxed = true)
        )
}