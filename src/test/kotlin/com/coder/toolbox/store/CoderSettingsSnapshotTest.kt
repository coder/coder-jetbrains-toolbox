package com.coder.toolbox.store

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.util.pluginTestSettingsStore
import com.squareup.moshi.Moshi
import io.mockk.mockk
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoderSettingsSnapshotTest {
    @Test
    fun `snapshot includes effective defaults and preserves JSON value types`() {
        val settings = CoderSettingsStore(
            pluginTestSettingsStore(), emptyEnvironment(), mockk(relaxed = true),
        ).readOnly()

        val snapshot = parse(settings.marshalToJson())

        assertEquals("https://dev.coder.com", snapshot.setting("defaultURL")["effective"])
        assertEquals(true, snapshot.setting("enableDownloads")["effective"])
        assertEquals(true, snapshot.setting("useKeyring")["effective"])
        assertEquals(false, snapshot.setting("disableSignatureValidation")["effective"])
        assertEquals(1000.0, snapshot.setting("connectionLogBufferSize")["effective"])
        assertEquals("NONE", snapshot.setting("httpClientLogLevel")["effective"])
        assertEquals("NOT_CONFIGURED", snapshot.setting("signatureFallbackStrategy")["effective"])
        assertEquals(settings.sshConfigPath, snapshot.setting("sshConfigPath")["effective"])
        assertTrue(snapshot.containsKey("binarySource"))
        assertEquals(null, snapshot.setting("binarySource")["effective"])
        assertEquals("<empty>", snapshot.setting("headerCommand")["effective"])
        assertEquals("<empty>", snapshot.setting("tlsCertRefreshCommand")["effective"])
        assertEquals("<empty>", snapshot.setting("sshConfigOptions")["effective"])
        snapshot.values.forEach { value ->
            val entry = value as Map<*, *>
            assertEquals(entry["defaultValue"], entry["effective"])
            assertTrue(entry.containsKey("storedValue"))
            assertTrue(entry["storedValue"] == null || entry["storedValue"] == "<empty>")
        }
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
        val settings = CoderSettingsStore(
            store,
            Environment(
                mapOf(
                    "CODER_HEADER_COMMAND" to "unused-header-secret",
                    "CODER_SSH_CONFIG_OPTIONS" to "unused-ssh-secret",
                ),
            ),
            mockk(relaxed = true),
        )
        val original = store.toMap()

        val bytes = settings.marshalToJson()
        val snapshot = parse(bytes)

        assertEquals("<set>", snapshot.setting("headerCommand")["effective"])
        assertEquals("<set>", snapshot.setting("tlsCertRefreshCommand")["effective"])
        assertEquals("<set>", snapshot.setting("sshConfigOptions")["effective"])
        assertEquals("/path/to/key.pem", snapshot.setting("tlsKeyPath")["effective"])
        assertEquals(store["binarySource"], snapshot.setting("binarySource")["effective"])
        assertEquals(store["binaryDestination"], snapshot.setting("binaryDestination")["effective"])
        assertEquals("HEADERS", snapshot.setting("httpClientLogLevel")["effective"])
        assertEquals(42.0, snapshot.setting("connectionLogBufferSize")["effective"])
        assertFalse(snapshot.containsKey("futureSetting"))
        assertFalse(snapshot.containsKey("sessionToken"))
        assertFalse(snapshot.containsKey("ssh_auto_connect_workspace-id"))
        for (key in listOf("headerCommand", "tlsCertRefreshCommand", "sshConfigOptions")) {
            assertEquals("<set>", snapshot.setting(key)["storedValue"])
            assertEquals("<empty>", snapshot.setting(key)["defaultValue"])
        }
        assertEquals("<set>", snapshot.setting("headerCommand")["environmentValue"])
        assertEquals("<set>", snapshot.setting("sshConfigOptions")["environmentValue"])
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

        val bytes = settings.marshalToJson()
        val snapshot = parse(bytes)

        assertEquals("<set>", snapshot.setting("headerCommand")["effective"])
        assertEquals("<set>", snapshot.setting("sshConfigOptions")["effective"])
        assertEquals("<empty>", snapshot.setting("tlsCertRefreshCommand")["effective"])
        assertEquals("/legacy/bin", snapshot.setting("binaryDestination")["effective"])
        assertEquals(1000.0, snapshot.setting("connectionLogBufferSize")["effective"])
        assertEquals("invalid", snapshot.setting("connectionLogBufferSize")["storedValue"])
        assertEquals("<empty>", snapshot.setting("headerCommand")["storedValue"])
        assertEquals("CODER_HEADER_COMMAND", snapshot.setting("headerCommand")["environmentVariable"])
        assertEquals("<set>", snapshot.setting("headerCommand")["environmentValue"])
        assertEquals("CODER_SSH_CONFIG_OPTIONS", snapshot.setting("sshConfigOptions")["environmentVariable"])
        assertEquals(null, snapshot.setting("binaryDestination")["defaultValue"])
        assertEquals(null, snapshot.setting("binaryDestination")["storedValue"])
        assertEquals("binaryDirectory", snapshot.setting("binaryDestination")["legacyKey"])
        assertEquals("/legacy/bin", snapshot.setting("binaryDestination")["legacyValue"])
        assertFalse(bytes.toString(Charsets.UTF_8).contains("-secret"))
    }

    @Test
    fun `overrides retain independent defaults and raw persisted values`() {
        val settings = CoderSettingsStore(
            pluginTestSettingsStore(
                "enableDownloads" to "false",
                "connectionLogBufferSize" to "0",
                "defaultURL" to "",
                "httpClientLogLevel" to "HeAdErS",
                "signatureFallbackStrategy" to "allow",
            ),
            emptyEnvironment(),
            mockk(relaxed = true),
        )

        val snapshot = parse(settings.marshalToJson())

        assertEquals(
            mapOf("defaultValue" to true, "storedValue" to "false", "effective" to false),
            snapshot.setting("enableDownloads"),
        )
        assertEquals(
            mapOf("defaultValue" to 1000.0, "storedValue" to "0", "effective" to 0.0),
            snapshot.setting("connectionLogBufferSize"),
        )
        assertEquals(
            mapOf("defaultValue" to "https://dev.coder.com", "storedValue" to "", "effective" to ""),
            snapshot.setting("defaultURL"),
        )
        assertEquals("HeAdErS", snapshot.setting("httpClientLogLevel")["storedValue"])
        assertEquals("NONE", snapshot.setting("httpClientLogLevel")["defaultValue"])
        assertEquals("HEADERS", snapshot.setting("httpClientLogLevel")["effective"])
        assertEquals("allow", snapshot.setting("signatureFallbackStrategy")["storedValue"])
        assertEquals("NOT_CONFIGURED", snapshot.setting("signatureFallbackStrategy")["defaultValue"])
        assertEquals("ALLOW", snapshot.setting("signatureFallbackStrategy")["effective"])
        assertEquals(false, settings.enableDownloads)
        assertEquals(0, settings.connectionLogBufferSize)
        assertEquals("", settings.defaultURL)
    }

    @Test
    fun `invalid values stay visible while getters and diagnostics use the same defaults`() {
        val settings = CoderSettingsStore(
            pluginTestSettingsStore(
                "enableDownloads" to "FALSE",
                "connectionLogBufferSize" to "10001",
                "sshConnectionTimeoutInSeconds" to "invalid",
                "httpClientLogLevel" to "invalid",
                "signatureFallbackStrategy" to "invalid",
            ),
            emptyEnvironment(),
            mockk(relaxed = true),
        )

        val snapshot = parse(settings.marshalToJson())

        for (key in listOf(
            "enableDownloads", "connectionLogBufferSize", "sshConnectionTimeoutInSeconds",
            "httpClientLogLevel", "signatureFallbackStrategy",
        )) {
            val entry = snapshot.setting(key)
            assertEquals(entry["defaultValue"], entry["effective"], key)
        }
        assertEquals("FALSE", snapshot.setting("enableDownloads")["storedValue"])
        assertEquals("10001", snapshot.setting("connectionLogBufferSize")["storedValue"])
        assertEquals(true, settings.enableDownloads)
        assertEquals(1000, settings.connectionLogBufferSize)
        assertEquals(0, settings.sshConnectionTimeoutInSeconds)
        assertEquals("NONE", settings.httpClientLogLevel.toString())
        assertEquals("NOT_CONFIGURED", settings.fallbackOnCoderForSignatures.toString())
    }

    @Test
    fun `paths retain raw overrides alongside resolved defaults and effective values`() {
        val settings = CoderSettingsStore(
            pluginTestSettingsStore(
                "sshConfigPath" to "~/custom-config",
                "sshLogDir" to "   ",
                "networkInfoDir" to "~/custom-metrics",
            ),
            emptyEnvironment(),
            mockk(relaxed = true),
        )
        val home = Path.of(System.getProperty("user.home"))

        val snapshot = parse(settings.marshalToJson())

        assertEquals("~/custom-config", snapshot.setting("sshConfigPath")["storedValue"])
        assertEquals(
            home.resolve(".ssh/config").normalize().toString(),
            snapshot.setting("sshConfigPath")["defaultValue"],
        )
        assertEquals(home.resolve("custom-config").toString(), snapshot.setting("sshConfigPath")["effective"])
        assertEquals(settings.sshConfigPath, snapshot.setting("sshConfigPath")["effective"])
        assertEquals("   ", snapshot.setting("sshLogDir")["storedValue"])
        assertEquals(null, snapshot.setting("sshLogDir")["effective"])
        assertEquals(null, settings.sshLogDirectory)
        assertEquals(
            Path.of(settings.globalDataDirectory).resolve("ssh-network-metrics").normalize().toString(),
            snapshot.setting("networkInfoDir")["defaultValue"],
        )
        assertEquals(home.resolve("custom-metrics").toString(), snapshot.setting("networkInfoDir")["effective"])
        assertEquals(settings.networkInfoDir, snapshot.setting("networkInfoDir")["effective"])
    }

    @Test
    fun `OS dependent defaults stay lazy and remain visible with an override`() {
        val settings = CoderSettingsStore(pluginTestSettingsStore(), emptyEnvironment(), mockk(relaxed = true))
        val originalOs = System.getProperty("os.name")
        try {
            for ((os, default) in listOf("Mac OS X" to true, "Linux" to false)) {
                System.setProperty("os.name", os)
                val entry = parse(settings.marshalToJson()).setting("disableAutostart")
                assertEquals(default, entry["defaultValue"])
                assertEquals(default, entry["effective"])
                assertEquals(default, settings.disableAutostart)
            }
            settings.updateDisableAutostart(true)
            val entry = parse(settings.marshalToJson()).setting("disableAutostart")
            assertEquals(false, entry["defaultValue"])
            assertEquals("true", entry["storedValue"])
            assertEquals(true, entry["effective"])
        } finally {
            if (originalOs == null) System.clearProperty("os.name") else System.setProperty("os.name", originalOs)
        }
    }

    @Test
    fun `stored values take precedence over environment and legacy sources and updates are reflected`() {
        val backing = pluginTestSettingsStore(
            "binaryDestination" to "",
            "binaryDirectory" to "/legacy/bin",
            "headerCommand" to "stored header",
            "sshConfigOptions" to "stored options",
        )
        val settings = CoderSettingsStore(
            backing,
            Environment(mapOf("CODER_HEADER_COMMAND" to "env header", "CODER_SSH_CONFIG_OPTIONS" to "env options")),
            mockk(relaxed = true),
        )
        assertEquals("", settings.binaryDestination)
        assertEquals("stored header", settings.headerCommand)
        assertEquals("stored options", settings.sshConfigOptions)
        assertEquals("", parse(settings.marshalToJson()).setting("binaryDestination")["effective"])

        settings.updateHeaderCommand("")
        settings.updateSshConfigOptions("")
        settings.updateBinaryDestination("/new/bin")
        assertEquals("env header", settings.headerCommand)
        assertEquals("env options", settings.sshConfigOptions)
        val snapshot = parse(settings.marshalToJson())
        assertEquals("/new/bin", snapshot.setting("binaryDestination")["storedValue"])
        assertEquals("/new/bin", snapshot.setting("binaryDestination")["effective"])
        assertEquals("/legacy/bin", snapshot.setting("binaryDestination")["legacyValue"])
        assertEquals("<empty>", snapshot.setting("headerCommand")["storedValue"])
        assertEquals("<set>", snapshot.setting("headerCommand")["effective"])
        assertEquals("<empty>", snapshot.setting("sshConfigOptions")["storedValue"])
        assertEquals("<set>", snapshot.setting("sshConfigOptions")["effective"])
    }

    private fun Map<*, *>.setting(key: String): Map<*, *> = requireNotNull(this[key] as? Map<*, *>)

    private fun emptyEnvironment() = Environment(
        mapOf("CODER_HEADER_COMMAND" to "", "CODER_SSH_CONFIG_OPTIONS" to ""),
    )

    private fun parse(bytes: ByteArray): Map<*, *> =
        requireNotNull(Moshi.Builder().build().adapter(Map::class.java).fromJson(bytes.toString(Charsets.UTF_8)))
}
