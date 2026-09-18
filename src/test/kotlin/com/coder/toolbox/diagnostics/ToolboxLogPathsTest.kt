package com.coder.toolbox.diagnostics

import com.coder.toolbox.util.OS
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolboxLogPathsTest {
    private val home = Path.of("test-home")

    private fun paths(os: OS, properties: Map<String, String> = emptyMap(), env: Map<String, String> = emptyMap()) =
        ToolboxLogPaths(os, home, properties::get, env::get, { null }).directories()

    @Test
    fun `default locations for Windows macOS and Linux`() {
        assertEquals(
            mapOf(
                "toolbox" to home.resolve("AppData/Local/JetBrains/Toolbox/logs"),
                "daemon" to home.resolve("AppData/Local/JetBrains/Daemon/logs"),
            ), paths(OS.WINDOWS)
        )
        assertEquals(
            mapOf(
                "toolbox" to home.resolve("Library/Logs/JetBrains/Toolbox"),
                "daemon" to home.resolve("Library/Logs/JetBrains/Daemon"),
            ), paths(OS.MAC)
        )
        assertEquals(
            mapOf(
                "toolbox" to home.resolve(".local/share/JetBrains/Toolbox/logs"),
                "daemon" to home.resolve(".local/share/JetBrains/Daemon/logs"),
            ), paths(OS.LINUX)
        )
    }

    @Test
    fun `Linux data override applies to both log directories`() {
        val result = paths(OS.LINUX, env = mapOf("XDG_DATA_HOME" to "custom-data"))
        assertEquals(Path.of("custom-data/JetBrains/Toolbox/logs"), result["toolbox"])
        assertEquals(Path.of("custom-data/JetBrains/Daemon/logs"), result["daemon"])
        assertEquals(paths(OS.LINUX), paths(OS.LINUX, env = mapOf("XDG_DATA_HOME" to " ")))
    }

    @Test
    fun `Windows uses the known folder for Toolbox and LOCALAPPDATA for the daemon`() {
        val result = ToolboxLogPaths(
            OS.WINDOWS, home, { null },
            { if (it == "LOCALAPPDATA") "daemon-data" else null },
            { Path.of("redirected-data") },
        ).directories()
        assertEquals(Path.of("redirected-data/JetBrains/Toolbox/logs"), result["toolbox"])
        assertEquals(Path.of("daemon-data/JetBrains/Daemon/logs"), result["daemon"])
    }

    @Test
    fun `explicit log overrides have priority on every platform`() {
        for (os in OS.entries) {
            assertEquals(
                mapOf(
                    "toolbox" to Path.of("custom-toolbox"),
                    "daemon" to Path.of("custom-daemon"),
                ), paths(
                    os,
                    properties = mapOf(
                        "toolbox.log.path" to "custom-toolbox",
                        "toolbox.localAppData.path" to "ignored"
                    ),
                    env = mapOf("JETBRAINS_DAEMON_LOG_DIRECTORY" to "custom-daemon", "XDG_DATA_HOME" to "ignored"),
                )
            )
        }
    }

    @Test
    fun `Toolbox data override does not change daemon logs and is ignored for macOS logs`() {
        for (os in OS.entries) {
            val result = paths(os, properties = mapOf("toolbox.localAppData.path" to "custom-data"))
            assertEquals(paths(os)["daemon"], result["daemon"])
            val expected = if (os == OS.MAC) paths(os)["toolbox"] else Path.of("custom-data/JetBrains/Toolbox/logs")
            assertEquals(expected, result["toolbox"])
        }
    }

    @Test
    fun `daemon snapshot override takes priority and daemon data override does not affect logs`() {
        for (os in OS.entries) {
            val result = paths(
                os, env = mapOf(
                    "JETBRAINS_DAEMON_SNAPSHOT_BASE_PATH" to "snapshot",
                    "JETBRAINS_DAEMON_LOG_DIRECTORY" to "ignored",
                )
            )
            assertEquals(Path.of("snapshot/logs"), result["daemon"])
            assertEquals(paths(os), paths(os, env = mapOf("JETBRAINS_DAEMON_DATA_DIRECTORY" to "ignored")))
        }
    }
}
