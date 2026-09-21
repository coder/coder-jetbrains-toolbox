package com.coder.toolbox.diagnostics

import com.coder.toolbox.util.OS
import com.coder.toolbox.util.getOS
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.Win32Exception
import java.nio.file.Path

/** Resolves the local log directories used by Toolbox and the JetBrains daemon. */
internal class ToolboxLogPaths(
    private val os: OS = requireNotNull(getOS()),
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val property: (String) -> String? = System::getProperty,
    private val environment: (String) -> String? = System::getenv,
    private val windowsLocalAppData: () -> Path? = ::windowsLocalAppData,
) {
    fun directories(): Map<String, Path> = linkedMapOf(
        "toolbox" to toolboxLogs(),
        "daemon" to daemonLogs(),
    )

    private fun toolboxLogs(): Path = property("toolbox.log.path")?.let(Path::of)
        ?: if (os == OS.MAC) {
            home.resolve("Library/Logs/JetBrains/Toolbox")
        } else {
            (property("toolbox.localAppData.path")?.let(Path::of) ?: toolboxData())
                .resolve("JetBrains/Toolbox/logs")
        }

    private fun daemonLogs(): Path = envPath("JETBRAINS_DAEMON_SNAPSHOT_BASE_PATH")?.resolve("logs")
        ?: envPath("JETBRAINS_DAEMON_LOG_DIRECTORY")
        ?: if (os == OS.MAC) {
            home.resolve("Library/Logs/JetBrains/Daemon")
        } else {
            systemData().resolve("JetBrains/Daemon/logs")
        }

    private fun systemData(): Path = when (os) {
        OS.WINDOWS -> envPath("LOCALAPPDATA") ?: home.resolve("AppData/Local")
        OS.LINUX -> envPath("XDG_DATA_HOME") ?: home.resolve(".local/share")
        OS.MAC -> home.resolve("Library/Application Support")
    }

    private fun toolboxData(): Path = if (os == OS.WINDOWS) {
        windowsLocalAppData() ?: home.resolve("AppData/Local")
    } else {
        systemData()
    }

    private fun envPath(name: String): Path? = environment(name)?.takeIf { it.isNotBlank() }?.let(Path::of)
}

private fun windowsLocalAppData(): Path? = try {
    Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_LocalAppData))
} catch (_: Win32Exception) {
    null
}
