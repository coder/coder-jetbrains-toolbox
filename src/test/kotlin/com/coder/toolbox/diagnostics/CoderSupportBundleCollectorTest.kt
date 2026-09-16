package com.coder.toolbox.diagnostics

import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderSupportBundleCollectorTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `writes the bundle inside Toolbox diagnostics`() = runTest {
        val collector = CoderSupportBundleCollector(mockk(relaxed = true)) {
            Files.writeString(it, "bundle")
        }
        collector.collectAdditionalDiagnostics(root.resolve("diagnostics"))
        assertEquals("bundle", Files.readString(root.resolve("diagnostics/coder-support.zip")))
    }

    @Test
    fun `failure removes partial bundle and preserves other diagnostics without leaking error details`() = runTest {
        Files.writeString(root.resolve("other.log"), "other diagnostics")
        val collector = CoderSupportBundleCollector(mockk(relaxed = true)) {
            Files.writeString(it, "partial")
            error("sensitive command output")
        }
        collector.collectAdditionalDiagnostics(root)
        assertFalse(Files.exists(root.resolve("coder-support.zip")))
        assertEquals("other diagnostics", Files.readString(root.resolve("other.log")))
        val report = Files.readString(root.resolve("coder-support-error.txt"))
        assertTrue(report.contains("IllegalStateException"))
        assertFalse(report.contains("sensitive command output"))
    }

    @Test
    fun `cancellation propagates and removes partial output`() = runTest {
        val collector = CoderSupportBundleCollector(mockk(relaxed = true)) {
            Files.writeString(it, "partial")
            throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> { collector.collectAdditionalDiagnostics(root) }
        assertFalse(Files.exists(root.resolve("coder-support.zip")))
        assertFalse(Files.exists(root.resolve("coder-support-error.txt")))
    }
}
