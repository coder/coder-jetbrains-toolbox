package com.coder.toolbox.cli

import com.coder.toolbox.cli.CoderCLIManager.Companion.getSshLogDirectory
import com.coder.toolbox.util.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SshLogDirectoryTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `uses the CLI state directory default on each platform`() {
        val defaults = mapOf(
            OS.LINUX to ".local/state/coder/logs",
            OS.MAC to "Library/Application Support/coder/logs",
            OS.WINDOWS to "AppData/Local/coder/logs",
        )
        for ((os, directory) in defaults) {
            assertEquals(home.resolve(directory), getSshLogDirectory(null, emptyMap(), os, home))
        }
    }

    @Test
    fun `configured directory takes precedence over environment overrides`() {
        val configured = home.resolve("configured logs")
        val env = mapOf(
            "CODER_SSH_LOG_DIR" to home.resolve("env-logs").toString(),
            "XDG_STATE_HOME" to home.resolve("state").toString(),
        )
        assertEquals(configured, getSshLogDirectory(configured.toString(), env, OS.LINUX, home))
        assertEquals(home.resolve("env-logs"), getSshLogDirectory(" ", env, OS.LINUX, home))
    }

    @Test
    fun `XDG state override takes precedence on every platform`() {
        val state = home.resolve("custom-state")
        val env = mapOf(
            "CODER_SSH_LOG_DIR" to "",
            "XDG_STATE_HOME" to state.toString(),
            "LOCALAPPDATA" to home.resolve("local-app-data").toString(),
        )
        for (os in OS.entries) {
            assertEquals(state.resolve("coder/logs"), getSshLogDirectory(null, env, os, home))
        }
    }

    @Test
    fun `relative XDG state overrides fall back to the platform default`() {
        for (state in listOf("", "relative/state")) {
            assertEquals(
                home.resolve(".local/state/coder/logs"),
                getSshLogDirectory(null, mapOf("XDG_STATE_HOME" to state), OS.LINUX, home),
            )
        }
    }

    @Test
    fun `expands home references in XDG paths using CLI platform conventions`() {
        val overrides = mapOf(
            OS.LINUX to listOf("~/state", "\$HOME/state"),
            OS.MAC to listOf("~/state", "\$HOME/state"),
            OS.WINDOWS to listOf("%USERPROFILE%/state"),
        )
        for ((os, values) in overrides) {
            for (value in values) {
                assertEquals(
                    home.resolve("state/coder/logs"),
                    getSshLogDirectory(null, mapOf("XDG_STATE_HOME" to value), os, home),
                )
            }
        }
    }

    @Test
    fun `uses Windows local app data when set`() {
        val localAppData = home.resolve("local-app-data")
        assertEquals(
            localAppData.resolve("coder/logs"),
            getSshLogDirectory(null, mapOf("LOCALAPPDATA" to localAppData.toString()), OS.WINDOWS, home),
        )
    }

    @Test
    fun `uses the CLI home environment when no state override is set`() {
        assertEquals(
            home.resolve(".local/state/coder/logs"),
            getSshLogDirectory(null, mapOf("HOME" to home.toString()), OS.LINUX),
        )
        assertEquals(
            home.resolve("AppData/Local/coder/logs"),
            getSshLogDirectory(null, mapOf("USERPROFILE" to home.toString()), OS.WINDOWS),
        )
    }
}
