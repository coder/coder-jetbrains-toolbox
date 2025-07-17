package com.coder.toolbox.store

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.util.pluginTestSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
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
        assertBinaryAndSignature("Windows 10", "amd64", "coder-windows-amd64.exe", "coder-windows-amd64.asc")

    @Test
    fun `Default CLI and signature for Windows ARM64`() =
        assertBinaryAndSignature("Windows 10", "aarch64", "coder-windows-arm64.exe", "coder-windows-arm64.asc")

    @Test
    fun `Default CLI and signature for Windows ARMV7`() =
        assertBinaryAndSignature("Windows 10", "armv7l", "coder-windows-armv7.exe", "coder-windows-armv7.asc")

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
    fun `Default CLI and signature for Mac ARMV7`() =
        assertBinaryAndSignature("Mac OS X", "armv7l", "coder-darwin-armv7", "coder-darwin-armv7.asc")

    @Test
    fun `Default CLI and signature for unknown OS and Arch`() =
        assertBinaryAndSignature(null, null, "coder-windows-amd64.exe", "coder-windows-amd64.asc")

    @Test
    fun `Default CLI and signature for unknown Arch fallback on Linux`() =
        assertBinaryAndSignature("Linux", "mips64", "coder-linux-amd64", "coder-linux-amd64.asc")

    private fun assertBinaryAndSignature(
        osName: String?,
        arch: String?,
        expectedBinary: String,
        expectedSignature: String
    ) {
        if (osName == null) System.clearProperty("os.name") else System.setProperty("os.name", osName)
        if (arch == null) System.clearProperty("os.arch") else System.setProperty("os.arch", arch)

        assertEquals(expectedBinary, store.defaultCliBinaryNameByOsAndArch)
        assertEquals(expectedSignature, store.defaultSignatureNameByOsAndArch)
    }

}