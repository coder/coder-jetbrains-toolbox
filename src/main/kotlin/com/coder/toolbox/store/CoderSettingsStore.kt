package com.coder.toolbox.store

import com.coder.toolbox.settings.CTLSSettings
import com.coder.toolbox.settings.CoderSettings
import com.coder.toolbox.settings.Environment
import com.coder.toolbox.settings.SettingSource
import com.coder.toolbox.util.Arch
import com.coder.toolbox.util.OS
import com.coder.toolbox.util.getArch
import com.coder.toolbox.util.getOS
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import java.nio.file.Path
import java.nio.file.Paths

class CoderSettingsStore(
    private val store: PluginSettingsStore,
    private val env: Environment = Environment(),
    private val logger: Logger
) {
    private var backingSettings = CoderSettings(
        defaultURL = store[DEFAULT_URL],
        binarySource = store[BINARY_SOURCE],
        binaryDirectory = store[BINARY_DIRECTORY],
        defaultCliBinaryNameByOsAndArch = getCoderCLIForOS(getOS(), getArch()),
        binaryName = store[BINARY_NAME] ?: getCoderCLIForOS(getOS(), getArch()),
        dataDirectory = store[DATA_DIRECTORY],
        globalDataDirectory = getDefaultGlobalDataDir().normalize().toString(),
        globalConfigDir = getDefaultGlobalConfigDir().normalize().toString(),
        enableDownloads = store[ENABLE_DOWNLOADS]?.toBooleanStrictOrNull() ?: true,
        enableBinaryDirectoryFallback = store[ENABLE_BINARY_DIR_FALLBACK]?.toBooleanStrictOrNull() ?: false,
        headerCommand = store[HEADER_COMMAND],
        tls = CTLSSettings(
            certPath = store[TLS_CERT_PATH],
            keyPath = store[TLS_KEY_PATH],
            caPath = store[TLS_CA_PATH],
            altHostname = store[TLS_ALTERNATE_HOSTNAME]
        ),
        disableAutostart = store[DISABLE_AUTOSTART]?.toBooleanStrictOrNull() ?: (getOS() == OS.MAC),
        isSshWildcardConfigEnabled = store[ENABLE_SSH_WILDCARD_CONFIG]?.toBooleanStrictOrNull() ?: false,
        sshConfigPath = store[SSH_CONFIG_PATH].takeUnless { it.isNullOrEmpty() }
            ?: Path.of(System.getProperty("user.home")).resolve(".ssh/config").normalize().toString(),
        sshLogDirectory = store[SSH_LOG_DIR],
        sshConfigOptions = store[SSH_CONFIG_OPTIONS].takeUnless { it.isNullOrEmpty() } ?: env.get(
            CODER_SSH_CONFIG_OPTIONS
        )
    )

    /**
     * The default URL to show in the connection window.
     */
    fun defaultURL(): Pair<String, SettingSource>? {
        val envURL = env.get(CODER_URL)
        if (!backingSettings.defaultURL.isNullOrEmpty()) {
            return backingSettings.defaultURL!! to SettingSource.SETTINGS
        } else if (envURL.isNotBlank()) {
            return envURL to SettingSource.ENVIRONMENT
        } else {
            val (configUrl, _) = backingSettings.readConfig(Path.of(backingSettings.globalConfigDir))
            if (!configUrl.isNullOrBlank()) {
                return configUrl to SettingSource.CONFIG
            }
        }
        return null
    }

    /**
     * Read-only access to the settings
     */
    fun readOnly(): CoderSettings = backingSettings

    fun updateBinarySource(source: String) {
        backingSettings = backingSettings.copy(binarySource = source)
        store[BINARY_SOURCE] = source
    }

    fun updateBinaryDirectory(dir: String) {
        backingSettings = backingSettings.copy(binaryDirectory = dir)
        store[BINARY_DIRECTORY] = dir
    }

    fun updateDataDirectory(dir: String) {
        backingSettings = backingSettings.copy(dataDirectory = dir)
        store[DATA_DIRECTORY] = dir
    }

    fun updateEnableDownloads(shouldEnableDownloads: Boolean) {
        backingSettings = backingSettings.copy(enableDownloads = shouldEnableDownloads)
        store[ENABLE_DOWNLOADS] = shouldEnableDownloads.toString()
    }

    fun updateBinaryDirectoryFallback(shouldEnableBinDirFallback: Boolean) {
        backingSettings = backingSettings.copy(enableBinaryDirectoryFallback = shouldEnableBinDirFallback)
        store[ENABLE_BINARY_DIR_FALLBACK] = shouldEnableBinDirFallback.toString()
    }

    fun updateHeaderCommand(cmd: String) {
        backingSettings = backingSettings.copy(headerCommand = cmd)
        store[HEADER_COMMAND] = cmd
    }

    fun updateCertPath(path: String) {
        backingSettings = backingSettings.copy(tls = backingSettings.tls.copy(certPath = path))
        store[TLS_CERT_PATH] = path
    }

    fun updateKeyPath(path: String) {
        backingSettings = backingSettings.copy(tls = backingSettings.tls.copy(keyPath = path))
        store[TLS_KEY_PATH] = path
    }

    fun updateCAPath(path: String) {
        backingSettings = backingSettings.copy(tls = backingSettings.tls.copy(caPath = path))
        store[TLS_CA_PATH] = path
    }

    fun updateAltHostname(hostname: String) {
        backingSettings = backingSettings.copy(tls = backingSettings.tls.copy(altHostname = hostname))
        store[TLS_ALTERNATE_HOSTNAME] = hostname
    }

    fun updateDisableAutostart(shouldDisableAutostart: Boolean) {
        backingSettings = backingSettings.copy(disableAutostart = shouldDisableAutostart)
        store[DISABLE_AUTOSTART] = shouldDisableAutostart.toString()
    }

    fun updateEnableSshWildcardConfig(enable: Boolean) {
        backingSettings = backingSettings.copy(isSshWildcardConfigEnabled = enable)
        store[ENABLE_SSH_WILDCARD_CONFIG] = enable.toString()
    }

    fun updateSshLogDir(path: String) {
        backingSettings = backingSettings.copy(sshLogDirectory = path)
        store[SSH_LOG_DIR] = path
    }

    fun updateSshConfigOptions(options: String) {
        backingSettings = backingSettings.copy(sshConfigOptions = options)
        store[SSH_CONFIG_OPTIONS] = options
    }

    private fun getDefaultGlobalDataDir(): Path {
        return when (getOS()) {
            OS.WINDOWS -> Paths.get(env.get("LOCALAPPDATA"), "coder-toolbox")
            OS.MAC -> Paths.get(env.get("HOME"), "Library/Application Support/coder-toolbox")
            else -> {
                val dir = env.get("XDG_DATA_HOME")
                if (dir.isNotBlank()) {
                    return Paths.get(dir, "coder-toolbox")
                }
                return Paths.get(env.get("HOME"), ".local/share/coder-toolbox")
            }
        }
    }

    private fun getDefaultGlobalConfigDir(): Path {
        var dir = env.get("CODER_CONFIG_DIR")
        if (dir.isNotBlank()) {
            return Path.of(dir)
        }
        // The Coder CLI uses https://github.com/kirsle/configdir so this should
        // match how it behaves.
        return when (getOS()) {
            OS.WINDOWS -> Paths.get(env.get("APPDATA"), "coderv2")
            OS.MAC -> Paths.get(env.get("HOME"), "Library/Application Support/coderv2")
            else -> {
                dir = env.get("XDG_CONFIG_HOME")
                if (dir.isNotBlank()) {
                    return Paths.get(dir, "coderv2")
                }
                return Paths.get(env.get("HOME"), ".config/coderv2")
            }
        }
    }

    /**
     * Return the name of the binary (with extension) for the provided OS and
     * architecture.
     */
    private fun getCoderCLIForOS(
        os: OS?,
        arch: Arch?,
    ): String {
        logger.info("Resolving binary for $os $arch")
        if (os == null) {
            logger.error("Could not resolve client OS and architecture, defaulting to WINDOWS AMD64")
            return "coder-windows-amd64.exe"
        }
        return when (os) {
            OS.WINDOWS ->
                when (arch) {
                    Arch.AMD64 -> "coder-windows-amd64.exe"
                    Arch.ARM64 -> "coder-windows-arm64.exe"
                    else -> "coder-windows-amd64.exe"
                }

            OS.LINUX ->
                when (arch) {
                    Arch.AMD64 -> "coder-linux-amd64"
                    Arch.ARM64 -> "coder-linux-arm64"
                    Arch.ARMV7 -> "coder-linux-armv7"
                    else -> "coder-linux-amd64"
                }

            OS.MAC ->
                when (arch) {
                    Arch.AMD64 -> "coder-darwin-amd64"
                    Arch.ARM64 -> "coder-darwin-arm64"
                    else -> "coder-darwin-amd64"
                }
        }
    }
}
