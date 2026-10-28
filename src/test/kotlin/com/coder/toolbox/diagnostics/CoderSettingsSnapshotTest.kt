package com.coder.toolbox.diagnostics

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.util.pluginTestSettingsStore
import com.squareup.moshi.Moshi
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderSettingsSnapshotTest {
    @Test
    fun `snapshot includes effective defaults and preserves JSON value types`() {
        val settings = settings()

        val snapshot = parse(collectSettingsSnapshot(settings))

        assertEquals("https://dev.coder.com", snapshot["defaultURL"])
        assertEquals(true, snapshot["enableDownloads"])
        assertEquals(true, snapshot["useKeyring"])
        assertEquals(false, snapshot["disableSignatureValidation"])
        assertEquals(1000.0, snapshot["connectionLogBufferSize"])
        assertEquals("NONE", snapshot["httpClientLogLevel"])
        assertEquals("NOT_CONFIGURED", snapshot["signatureFallbackStrategy"])
        assertEquals(settings.sshConfigPath, snapshot["sshConfigPath"])
        assertTrue(snapshot.containsKey("binarySource"))
        assertEquals(null, snapshot["binarySource"])
        assertEquals("<empty>", snapshot["headerCommand"])
        assertEquals("<empty>", snapshot["tlsCertRefreshCommand"])
        assertEquals("<empty>", snapshot["sshConfigOptions"])
    }

    @Test
    fun `snapshot masks commands and SSH options and excludes unreviewed settings`() {
        val store = pluginTestSettingsStore(
            "headerCommand" to "echo header-secret",
            "tlsCertRefreshCommand" to "renew --token tls-secret",
            "sshConfigOptions" to "ProxyCommand=connect --password ssh-secret",
            "sessionToken" to "session-secret",
            "futureSetting" to "unknown-secret",
            "ssh_auto_connect_workspace-id" to "true",
            "tlsKeyPath" to "/path/to/key.pem",
            "binarySource" to "https://coder.example/bin/cli",
            "binaryDestination" to "C:\\Users\\Zoë\\\"Coder\"\ncli",
            "httpClientLogLevel" to "headers",
            "connectionLogBufferSize" to "42",
        )
        val settings = CoderSettingsStore(store, emptyEnvironment(), mockk(relaxed = true))
        val original = store.toMap()

        val bytes = collectSettingsSnapshot(settings)
        val snapshot = parse(bytes)

        assertEquals("<set>", snapshot["headerCommand"])
        assertEquals("<set>", snapshot["tlsCertRefreshCommand"])
        assertEquals("<set>", snapshot["sshConfigOptions"])
        assertEquals("/path/to/key.pem", snapshot["tlsKeyPath"])
        assertEquals(store["binarySource"], snapshot["binarySource"])
        assertEquals(store["binaryDestination"], snapshot["binaryDestination"])
        assertEquals("HEADERS", snapshot["httpClientLogLevel"])
        assertEquals(42.0, snapshot["connectionLogBufferSize"])
        assertFalse(snapshot.containsKey("futureSetting"))
        assertFalse(snapshot.containsKey("sessionToken"))
        assertFalse(snapshot.containsKey("ssh_auto_connect_workspace-id"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("-secret"))
        assertEquals(original, store.toMap())
    }

    @Test
    fun `environment fallbacks are masked and legacy settings resolve to effective values`() {
        val settings = CoderSettingsStore(
            pluginTestSettingsStore(
                "headerCommand" to "",
                "sshConfigOptions" to "",
                "tlsCertRefreshCommand" to "",
                "binaryDirectory" to "/legacy/bin",
                "connectionLogBufferSize" to "invalid",
            ),
            Environment(
                mapOf(
                    "CODER_HEADER_COMMAND" to "echo environment-header-secret",
                    "CODER_SSH_CONFIG_OPTIONS" to "ProxyCommand=environment-ssh-secret",
                ),
            ),
            mockk(relaxed = true),
        )

        val bytes = collectSettingsSnapshot(settings)
        val snapshot = parse(bytes)

        assertEquals("<set>", snapshot["headerCommand"])
        assertEquals("<set>", snapshot["sshConfigOptions"])
        assertEquals("<empty>", snapshot["tlsCertRefreshCommand"])
        assertEquals("/legacy/bin", snapshot["binaryDestination"])
        assertEquals(1000.0, snapshot["connectionLogBufferSize"])
        assertFalse(bytes.toString(Charsets.UTF_8).contains("-secret"))
    }

    private fun settings() = CoderSettingsStore(pluginTestSettingsStore(), emptyEnvironment(), mockk(relaxed = true))

    private fun emptyEnvironment() = Environment(
        mapOf("CODER_HEADER_COMMAND" to "", "CODER_SSH_CONFIG_OPTIONS" to ""),
    )

    private fun parse(bytes: ByteArray): Map<*, *> =
        requireNotNull(Moshi.Builder().build().adapter(Map::class.java).fromJson(bytes.toString(Charsets.UTF_8)))
}
