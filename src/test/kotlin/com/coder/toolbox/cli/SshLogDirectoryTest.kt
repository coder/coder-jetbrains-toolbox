package com.coder.toolbox.cli

import com.coder.toolbox.cli.CoderCLIManager.Companion.getSshLogDirectory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SshLogDirectoryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `uses plugin directory regardless of CLI state environment`() {
        val default = root.resolve("ssh-logs")
        assertEquals(default, getSshLogDirectory(null, default, mapOf("XDG_STATE_HOME" to "/state")))
    }

    @Test
    fun `configured directory takes precedence over environment`() {
        val configured = root.resolve("configured logs")
        val env = mapOf("CODER_SSH_LOG_DIR" to root.resolve("env-logs").toString())
        assertEquals(configured, getSshLogDirectory(configured.toString(), root, env))
        assertEquals(root.resolve("env-logs"), getSshLogDirectory(" ", root, env))
    }

    @Test
    fun `blank overrides use the plugin directory`() {
        assertEquals(root, getSshLogDirectory(" ", root, mapOf("CODER_SSH_LOG_DIR" to "")))
    }
}
