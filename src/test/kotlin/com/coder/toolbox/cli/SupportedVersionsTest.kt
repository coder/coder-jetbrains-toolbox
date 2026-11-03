package com.coder.toolbox.cli

import com.coder.toolbox.util.SemVer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SupportedVersionsTest {
    @Test
    fun `minimum and later release and development versions are supported`() {
        for (version in listOf("2.29.0", "v2.29.0", "v2.29.1", "v2.40.0-devel+abcdef", "v0.0.0-devel+abcdef", "v3.0.0")) {
            SupportedVersions.requireSupportedServer(version)
            SupportedVersions.requireSupportedCli(SemVer.parse(version))
        }
    }

    @Test
    fun `older and unknown versions are rejected`() {
        for (version in listOf("v2.28.99", "v2.4.0", "v1.99.0")) {
            val server =
                assertFailsWith<IllegalStateException>(version) { SupportedVersions.requireSupportedServer(version) }
            assertEquals("Your Coder server is too old. Please upgrade to v2.29.0 or newer.", server.message)
            val cli = assertFailsWith<IllegalStateException>(version) {
                SupportedVersions.requireSupportedCli(SemVer.parse(version))
            }
            assertEquals("Your Coder CLI is too old. Please upgrade to v2.29.0 or newer.", cli.message)
        }
        for (version in listOf("development", "")) {
            assertFailsWith<IllegalStateException>(version) { SupportedVersions.requireSupportedServer(version) }
        }
    }
}
