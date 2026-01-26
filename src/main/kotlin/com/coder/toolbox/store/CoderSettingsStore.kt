package com.coder.toolbox.store

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.settings.HttpLoggingVerbosity
import com.coder.toolbox.settings.ReadOnlyCoderSettings
import com.coder.toolbox.settings.ReadOnlyTLSSettings
import com.coder.toolbox.settings.SignatureFallbackStrategy
import com.coder.toolbox.util.Arch
import com.coder.toolbox.util.OS
import com.coder.toolbox.util.expand
import com.coder.toolbox.util.getArch
import com.coder.toolbox.util.getOS
import com.coder.toolbox.util.safeHost
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.withPath
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


class CoderSettingsStore(
    private val store: PluginSettingsStore,
    private val env: Environment = Environment(),
    private val logger: Logger
) : ReadOnlyCoderSettings {

    // Internal TLS settings implementation
    private class TLSSettings(
        override val certPath: String?,
        override val keyPath: String?,
        override val caPath: String?,
        override val altHostname: String?,
        override val certRefreshCommand: String?
    ) : ReadOnlyTLSSettings

    // Properties implementation
    override val lastDeploymentURL: String? get() = store[LAST_USED_URL]
    override val defaultURL: String get() = store[DEFAULT_URL] ?: "https://dev.coder.com"
    override val useAppNameAsTitle: Boolean get() = store[APP_NAME_AS_TITLE]?.toBooleanStrictOrNull() ?: false
    override val binarySource: String? get() = store[BINARY_SOURCE]
    override val binaryDirectory: String? get() = store[BINARY_DIRECTORY]
    override val disableSignatureVerification: Boolean
        get() = store[DISABLE_SIGNATURE_VALIDATION]?.toBooleanStrictOrNull() ?: false
    override val fallbackOnCoderForSignatures: SignatureFallbackStrategy
        get() = SignatureFallbackStrategy.fromValue(store[FALLBACK_ON_CODER_FOR_SIGNATURES])
    override val httpClientLogLevel: HttpLoggingVerbosity
        get() = HttpLoggingVerbosity.fromValue(store[HTTP_CLIENT_LOG_LEVEL])
    override val defaultCliBinaryNameByOsAndArch: String get() = getCoderCLIForOS(getOS(), getArch())
    override val binaryName: String get() = store[BINARY_NAME] ?: getCoderCLIForOS(getOS(), getArch())
    override val defaultSignatureNameByOsAndArch: String get() = getCoderSignatureForOS(getOS(), getArch())
    override val dataDirectory: String? get() = store[DATA_DIRECTORY]
    override val globalDataDirectory: String get() = getDefaultGlobalDataDir().normalize().toString()
    override val globalConfigDir: String get() = getDefaultGlobalConfigDir().normalize().toString()
    override val enableDownloads: Boolean get() = store[ENABLE_DOWNLOADS]?.toBooleanStrictOrNull() ?: true
    override val enableBinaryDirectoryFallback: Boolean
        get() = store[ENABLE_BINARY_DIR_FALLBACK]?.toBooleanStrictOrNull() ?: false
    override val headerCommand: String? get() = store[HEADER_COMMAND]
    override val tls: ReadOnlyTLSSettings
        get() = TLSSettings(
            certPath = store[TLS_CERT_PATH],
            keyPath = store[TLS_KEY_PATH],
            caPath = store[TLS_CA_PATH],
            altHostname = store[TLS_ALTERNATE_HOSTNAME],
            certRefreshCommand = store[TLS_CERT_REFRESH_COMMAND]
        )
    override val requiresTokenAuth: Boolean get() = tls.certPath.isNullOrBlank() || tls.keyPath.isNullOrBlank()
    override val requiresMTlsAuth: Boolean get() = tls.certPath?.isNotBlank() == true && tls.keyPath?.isNotBlank() == true
    override val disableAutostart: Boolean
        get() = store[DISABLE_AUTOSTART]?.toBooleanStrictOrNull() ?: (getOS() == OS.MAC)
    override val sshConnectionTimeoutInSeconds: Int
        get() = store[SSH_CONNECTION_TIMEOUT_IN_SECONDS]?.toIntOrNull() ?: 10
    override val isSshWildcardConfigEnabled: Boolean
        get() = store[ENABLE_SSH_WILDCARD_CONFIG]?.toBooleanStrictOrNull() ?: true
    override val sshConfigPath: String
        get() = store[SSH_CONFIG_PATH].takeUnless { it.isNullOrEmpty() }
            ?: Path.of(System.getProperty("user.home")).resolve(".ssh/config").normalize().toString()
    override val sshLogDirectory: String? get() = store[SSH_LOG_DIR]
    override val sshConfigOptions: String?
        get() = store[SSH_CONFIG_OPTIONS].takeUnless { it.isNullOrEmpty() } ?: env.get(CODER_SSH_CONFIG_OPTIONS)
    override val networkInfoDir: String
        get() = store[NETWORK_INFO_DIR].takeUnless { it.isNullOrEmpty() } ?: getDefaultGlobalDataDir()
            .resolve("ssh-network-metrics")
            .normalize()
            .toString()

    override val workspaceViewUrl: String?
        get() = store[WORKSPACE_VIEW_URL]
    override val workspaceCreateUrl: String?
        get() = store[WORKSPACE_CREATE_URL]

    /**
     * Where the specified deployment should put its data.
     */
    override fun dataDir(url: URL): Path {
        dataDirectory.let {
            val dir =
                if (it.isNullOrBlank()) {
                    Path.of(globalDataDirectory)
                } else {
                    Path.of(expand(it))
                }
            return withHost(dir, url).toAbsolutePath()
        }
    }

    /**
     * From where the specified deployment should download the binary.
     */
    override fun binSource(url: URL): URL {
        binarySource.let {
            return if (it.isNullOrBlank()) {
                url.withPath("/bin/$defaultCliBinaryNameByOsAndArch")
            } else {
                try {
                    it.toURL()
                } catch (_: Exception) {
                    url.withPath(it) // Assume a relative path.
                }
            }
        }
    }

    /**
     * To where the specified deployment should download the binary.
     */
    override fun binPath(
        url: URL,
        forceDownloadToData: Boolean,
    ): Path {
        binaryDirectory.let {
            val dir =
                if (forceDownloadToData || it.isNullOrBlank()) {
                    dataDir(url)
                } else {
                    withHost(Path.of(expand(it)), url)
                }
            return dir.resolve(binaryName).toAbsolutePath()
        }
    }

    /**
     * Return the URL and token from the config, if they exist.
     */
    override fun readConfig(dir: Path): Pair<String?, String?> {
        return try {
            Files.readString(dir.resolve("url"))
        } catch (e: Exception) {
            // SSH has not been configured yet, or using some other authorization mechanism.
            null
        } to
                try {
                    Files.readString(dir.resolve("session"))
                } catch (e: Exception) {
                    // SSH has not been configured yet, or using some other authorization mechanism.
                    null
                }
    }

    override fun shouldAutoConnect(workspaceId: String): Boolean {
        return store["$SSH_AUTO_CONNECT_PREFIX$workspaceId"]?.toBooleanStrictOrNull() ?: false
    }

    // a readonly cast
    fun readOnly(): ReadOnlyCoderSettings = this

    // Write operations
    fun updateLastUsedUrl(url: URL) {
        store[LAST_USED_URL] = url.toString()
    }

    fun updateUseAppNameAsTitle(appNameAsTitle: Boolean) {
        store[APP_NAME_AS_TITLE] = appNameAsTitle.toString()
    }

    fun updateBinarySource(source: String) {
        store[BINARY_SOURCE] = source
    }

    fun updateBinaryDirectory(dir: String) {
        store[BINARY_DIRECTORY] = dir
    }

    fun updateDataDirectory(dir: String) {
        store[DATA_DIRECTORY] = dir
    }

    fun updateEnableDownloads(shouldEnableDownloads: Boolean) {
        store[ENABLE_DOWNLOADS] = shouldEnableDownloads.toString()
    }

    fun updateDisableSignatureVerification(shouldDisableSignatureVerification: Boolean) {
        store[DISABLE_SIGNATURE_VALIDATION] = shouldDisableSignatureVerification.toString()
    }

    fun updateSignatureFallbackStrategy(fallback: Boolean) {
        store[FALLBACK_ON_CODER_FOR_SIGNATURES] = when (fallback) {
            true -> SignatureFallbackStrategy.ALLOW.toString()
            else -> SignatureFallbackStrategy.FORBIDDEN.toString()
        }
    }

    fun updateHttpClientLogLevel(level: HttpLoggingVerbosity?) {
        if (level == null) return
        store[HTTP_CLIENT_LOG_LEVEL] = level.toString()
    }

    fun updateBinaryDirectoryFallback(shouldEnableBinDirFallback: Boolean) {
        store[ENABLE_BINARY_DIR_FALLBACK] = shouldEnableBinDirFallback.toString()
    }

    fun updateHeaderCommand(cmd: String) {
        store[HEADER_COMMAND] = cmd
    }

    fun updateCertPath(path: String) {
        store[TLS_CERT_PATH] = path
    }

    fun updateKeyPath(path: String) {
        store[TLS_KEY_PATH] = path
    }

    fun updateCAPath(path: String) {
        store[TLS_CA_PATH] = path
    }

    fun updateAltHostname(hostname: String) {
        store[TLS_ALTERNATE_HOSTNAME] = hostname
    }

    fun updateDisableAutostart(shouldDisableAutostart: Boolean) {
        store[DISABLE_AUTOSTART] = shouldDisableAutostart.toString()
    }

    fun updateSshConnectionTimeoutInSeconds(sshConnectionTimeoutInSeconds: Int) {
        store[SSH_CONNECTION_TIMEOUT_IN_SECONDS] = sshConnectionTimeoutInSeconds.toString()
    }

    fun updateEnableSshWildcardConfig(enable: Boolean) {
        store[ENABLE_SSH_WILDCARD_CONFIG] = enable.toString()
    }

    fun updateSshLogDir(path: String) {
        store[SSH_LOG_DIR] = path
    }

    fun updateNetworkInfoDir(path: String) {
        store[NETWORK_INFO_DIR] = path
    }

    fun updateSshConfigOptions(options: String) {
        store[SSH_CONFIG_OPTIONS] = options
    }

    fun updateAutoConnect(workspaceId: String, autoConnect: Boolean) {
        store["$SSH_AUTO_CONNECT_PREFIX$workspaceId"] = autoConnect.toString()
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
     * Return the name of the binary (with extension) for the provided OS and architecture.
     */
    private fun getCoderCLIForOS(os: OS?, arch: Arch?): String {
        logger.debug("Resolving binary for $os $arch")

        val (osName, extension) = when (os) {
            OS.WINDOWS -> "windows" to ".exe"
            OS.LINUX -> "linux" to ""
            OS.MAC -> "darwin" to ""
            null -> {
                logger.error("Could not resolve client OS and architecture, defaulting to WINDOWS AMD64")
                return "coder-windows-amd64.exe"
            }
        }

        val archName = when (arch) {
            Arch.AMD64 -> "amd64"
            Arch.ARM64 -> "arm64"
            Arch.ARMV7 -> "armv7"
            else -> "amd64" // default fallback
        }

        return "coder-$osName-$archName$extension"
    }

    /**
     * Return the name of the signature file (.asc) for the provided OS and architecture.
     */
    private fun getCoderSignatureForOS(os: OS?, arch: Arch?): String {
        logger.debug("Resolving signature for $os $arch")
        return "${getCoderCLIForOS(os, arch)}.asc"
    }

    /**
     * Append the host to the path.  For example, foo/bar could become
     * foo/bar/dev.coder.com-8080.
     */
    private fun withHost(
        path: Path,
        url: URL,
    ): Path {
        val host = if (url.port > 0) "${url.safeHost()}-${url.port}" else url.safeHost()
        return path.resolve(host)
    }
}