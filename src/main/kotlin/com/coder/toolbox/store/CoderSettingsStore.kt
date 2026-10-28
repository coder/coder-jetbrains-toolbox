package com.coder.toolbox.store

import com.coder.toolbox.settings.CONNECTION_LOG_BUFFER_SIZE_RANGE
import com.coder.toolbox.settings.DEFAULT_CONNECTION_LOG_BUFFER_SIZE
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
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// Defaults shared by the getters and the JSON export, so both always report the same fallback.
private const val DEFAULT_DEPLOYMENT_URL = "https://dev.coder.com"
private const val DEFAULT_APP_NAME_AS_TITLE = false
private const val DEFAULT_DISABLE_SIGNATURE_VALIDATION = false
private const val DEFAULT_ENABLE_DOWNLOADS = true
private const val DEFAULT_USE_KEYRING = true
private const val DEFAULT_SSH_CONNECTION_TIMEOUT_IN_SECONDS = 0
private const val DEFAULT_ENABLE_SSH_WILDCARD_CONFIG = true
private const val DEFAULT_PREFER_OAUTH2_IF_AVAILABLE = false

class CoderSettingsStore(
    private val store: PluginSettingsStore,
    private val env: Environment = Environment(),
    private val logger: Logger
) : ReadOnlyCoderSettings {

    private val settingsLock = Any()
    private val revision = MutableStateFlow(0L)

    // Internal TLS settings implementation
    private data class TLSSettings(
        override val certPath: String?,
        override val keyPath: String?,
        override val caPath: String?,
        override val altHostname: String?,
        override val certRefreshCommand: String?
    ) : ReadOnlyTLSSettings

    // Properties implementation
    override val lastDeploymentURL: String? get() = store[LAST_USED_URL]
    override val defaultURL: String get() = store[DEFAULT_URL] ?: DEFAULT_DEPLOYMENT_URL
    override val useAppNameAsTitle: Boolean
        get() = store[APP_NAME_AS_TITLE]?.toBooleanStrictOrNull() ?: DEFAULT_APP_NAME_AS_TITLE
    override val binarySource: String? get() = store[BINARY_SOURCE]
    override val binaryDestination: String? get() = store[BINARY_DESTINATION] ?: store[BINARY_DIRECTORY]
    override val disableSignatureVerification: Boolean
        get() = store[DISABLE_SIGNATURE_VALIDATION]?.toBooleanStrictOrNull()
            ?: DEFAULT_DISABLE_SIGNATURE_VALIDATION
    override val fallbackOnCoderForSignatures: SignatureFallbackStrategy
        get() = SignatureFallbackStrategy.fromValue(store[FALLBACK_ON_CODER_FOR_SIGNATURES])
    override val httpClientLogLevel: HttpLoggingVerbosity
        get() = HttpLoggingVerbosity.fromValue(store[HTTP_CLIENT_LOG_LEVEL])
    override val connectionLogBufferSize: Int
        get() = store[CONNECTION_LOG_BUFFER_SIZE]?.toIntOrNull()
            ?.takeIf { it in CONNECTION_LOG_BUFFER_SIZE_RANGE } ?: DEFAULT_CONNECTION_LOG_BUFFER_SIZE
    override val defaultCliBinaryNameByOsAndArch: String get() = getCoderCLIForOS(getOS(), getArch())
    override val defaultSignatureNameByOsAndArch: String get() = getCoderSignatureForOS(getOS(), getArch())
    override val dataDirectory: String? get() = store[DATA_DIRECTORY]
    override val globalDataDirectory: String get() = getDefaultGlobalDataDir().normalize().toString()
    override val globalConfigDir: String get() = getDefaultGlobalConfigDir().normalize().toString()
    override val enableDownloads: Boolean
        get() = store[ENABLE_DOWNLOADS]?.toBooleanStrictOrNull() ?: DEFAULT_ENABLE_DOWNLOADS
    override val headerCommand: String?
        get() = store[HEADER_COMMAND].takeUnless { it.isNullOrEmpty() }
            ?: env.get(CODER_HEADER_COMMAND).takeUnless { it.isEmpty() }
    override val useKeyring: Boolean get() = store[USE_KEYRING]?.toBooleanStrictOrNull() ?: DEFAULT_USE_KEYRING
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
        get() = store[DISABLE_AUTOSTART]?.toBooleanStrictOrNull() ?: defaultDisableAutostart()
    override val sshConnectionTimeoutInSeconds: Int
        get() = store[SSH_CONNECTION_TIMEOUT_IN_SECONDS]?.toIntOrNull()
            ?: DEFAULT_SSH_CONNECTION_TIMEOUT_IN_SECONDS
    override val isSshWildcardConfigEnabled: Boolean
        get() = store[ENABLE_SSH_WILDCARD_CONFIG]?.toBooleanStrictOrNull() ?: DEFAULT_ENABLE_SSH_WILDCARD_CONFIG
    override val sshConfigPath: String
        get() = store[SSH_CONFIG_PATH]?.takeIf { it.isNotBlank() }?.let { expand(it) } ?: defaultSshConfigPath()
    override val sshLogDirectory: String? get() = store[SSH_LOG_DIR]?.takeIf { it.isNotBlank() }?.let { expand(it) }
    override val sshConfigOptions: String?
        get() = store[SSH_CONFIG_OPTIONS].takeUnless { it.isNullOrEmpty() } ?: env.get(CODER_SSH_CONFIG_OPTIONS)
    override val networkInfoDir: String
        get() = store[NETWORK_INFO_DIR]?.takeIf { it.isNotBlank() }?.let { expand(it) } ?: defaultNetworkInfoDir()
    override val preferOAuth2IfAvailable: Boolean
        get() = store[PREFER_OAUTH2_IF_AVAILABLE]?.toBooleanStrictOrNull() ?: DEFAULT_PREFER_OAUTH2_IF_AVAILABLE
    override val workspaceViewUrl: String?
        get() = store[WORKSPACE_VIEW_URL]
    override val workspaceCreateUrl: String?
        get() = store[WORKSPACE_CREATE_URL]

    // Effective values come from the public getters, so the JSON cannot diverge from what the plugin uses.
    override fun marshalToJson(): ByteArray {
        val snapshot = synchronized(settingsLock) {
            val tls = tls
            // Keep this allowlist explicit: new settings must be reviewed for secrets before including them.
            sortedMapOf(
                jsonEntry(LAST_USED_URL, null, lastDeploymentURL),
                jsonEntry(DEFAULT_URL, DEFAULT_DEPLOYMENT_URL, defaultURL),
                jsonEntry(APP_NAME_AS_TITLE, DEFAULT_APP_NAME_AS_TITLE, useAppNameAsTitle),
                jsonEntry(BINARY_SOURCE, null, binarySource),
                jsonEntry(BINARY_DESTINATION, null, binaryDestination, legacyKey = BINARY_DIRECTORY),
                jsonEntry(
                    DISABLE_SIGNATURE_VALIDATION, DEFAULT_DISABLE_SIGNATURE_VALIDATION, disableSignatureVerification,
                ),
                jsonEntry(
                    FALLBACK_ON_CODER_FOR_SIGNATURES,
                    SignatureFallbackStrategy.fromValue(null),
                    fallbackOnCoderForSignatures,
                ),
                jsonEntry(HTTP_CLIENT_LOG_LEVEL, HttpLoggingVerbosity.fromValue(null), httpClientLogLevel),
                jsonEntry(CONNECTION_LOG_BUFFER_SIZE, DEFAULT_CONNECTION_LOG_BUFFER_SIZE, connectionLogBufferSize),
                jsonEntry(DATA_DIRECTORY, null, dataDirectory),
                jsonEntry(ENABLE_DOWNLOADS, DEFAULT_ENABLE_DOWNLOADS, enableDownloads),
                jsonEntry(
                    HEADER_COMMAND, null, headerCommand,
                    shouldBeMasked = true, environmentVariable = CODER_HEADER_COMMAND,
                ),
                jsonEntry(USE_KEYRING, DEFAULT_USE_KEYRING, useKeyring),
                jsonEntry(TLS_CERT_PATH, null, tls.certPath),
                jsonEntry(TLS_KEY_PATH, null, tls.keyPath),
                jsonEntry(TLS_CA_PATH, null, tls.caPath),
                jsonEntry(TLS_ALTERNATE_HOSTNAME, null, tls.altHostname),
                jsonEntry(TLS_CERT_REFRESH_COMMAND, null, tls.certRefreshCommand, shouldBeMasked = true),
                jsonEntry(DISABLE_AUTOSTART, defaultDisableAutostart(), disableAutostart),
                jsonEntry(
                    SSH_CONNECTION_TIMEOUT_IN_SECONDS,
                    DEFAULT_SSH_CONNECTION_TIMEOUT_IN_SECONDS,
                    sshConnectionTimeoutInSeconds,
                ),
                jsonEntry(ENABLE_SSH_WILDCARD_CONFIG, DEFAULT_ENABLE_SSH_WILDCARD_CONFIG, isSshWildcardConfigEnabled),
                jsonEntry(SSH_CONFIG_PATH, defaultSshConfigPath(), sshConfigPath),
                jsonEntry(SSH_LOG_DIR, null, sshLogDirectory),
                jsonEntry(
                    SSH_CONFIG_OPTIONS, "", sshConfigOptions,
                    shouldBeMasked = true, environmentVariable = CODER_SSH_CONFIG_OPTIONS,
                ),
                jsonEntry(NETWORK_INFO_DIR, defaultNetworkInfoDir(), networkInfoDir),
                jsonEntry(PREFER_OAUTH2_IF_AVAILABLE, DEFAULT_PREFER_OAUTH2_IF_AVAILABLE, preferOAuth2IfAvailable),
                jsonEntry(WORKSPACE_VIEW_URL, null, workspaceViewUrl),
                jsonEntry(WORKSPACE_CREATE_URL, null, workspaceCreateUrl),
            )
        }
        val json = Moshi.Builder().build().adapter(Map::class.java).serializeNulls().indent("  ").toJson(snapshot)
        return "$json\n".toByteArray(Charsets.UTF_8)
    }

    private fun jsonEntry(
        key: String,
        default: Any?,
        effective: Any?,
        shouldBeMasked: Boolean = false,
        environmentVariable: String? = null,
        legacyKey: String? = null,
    ): Pair<String, Map<String, Any?>> {
        fun toJsonValue(value: Any?): Any? = when {
            shouldBeMasked -> if (value == null || value == "") "<empty>" else "<set>"
            value is Enum<*> -> value.toString()
            else -> value
        }

        val entry = linkedMapOf(
            "defaultValue" to toJsonValue(default),
            "storedValue" to toJsonValue(store[key]),
            "effective" to toJsonValue(effective),
        )
        if (environmentVariable != null) {
            entry["environmentVariable"] = environmentVariable
            entry["environmentValue"] = toJsonValue(env.get(environmentVariable))
        }
        if (legacyKey != null) {
            entry["legacyKey"] = legacyKey
            entry["legacyValue"] = toJsonValue(store[legacyKey])
        }
        return key to entry
    }

    private fun defaultDisableAutostart(): Boolean = getOS() == OS.MAC

    private fun defaultSshConfigPath(): String =
        Path.of(System.getProperty("user.home")).resolve(".ssh/config").normalize().toString()

    private fun defaultNetworkInfoDir(): String =
        getDefaultGlobalDataDir().resolve("ssh-network-metrics").normalize().toString()

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
     * To where the specified deployment should place the CLI binary.
     *
     * Resolution logic:
     * 1. If [binaryDestination] is null/blank, return the deployment's data
     *    directory with the default CLI binary name.
     * 2. If the expanded (~ and $HOME) [binaryDestination] is an existing executable file,
     *    return it as-is.
     * 3. Otherwise, treat [binaryDestination] as a base directory and return a
     *    host-specific subdirectory with the default CLI binary name.
     */
    override fun binPath(url: URL): Path {
        val raw = binaryDestination?.takeIf { it.isNotBlank() } ?: return dataDir(url).resolve(
            defaultCliBinaryNameByOsAndArch
        ).toAbsolutePath()

        val dest = Path.of(expand(raw))
        if (Files.isRegularFile(dest) && Files.isExecutable(dest)) {
            return dest.toAbsolutePath()
        }
        return withHost(dest, url).resolve(defaultCliBinaryNameByOsAndArch).toAbsolutePath()
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

    override fun <T> observe(select: (ReadOnlyCoderSettings) -> T): Flow<T> = revision
        .map { synchronized(settingsLock) { select(this) } }
        .distinctUntilChanged()

    private fun writeSetting(key: String, value: String) {
        synchronized(settingsLock) {
            store[key] = value
            // A failed write leaves the revision unchanged. Selectors share this lock so they
            // cannot read an update before the backing store's write has returned.
            revision.value += 1
        }
    }

    // Write operations
    fun updateLastUsedUrl(url: URL) {
        writeSetting(LAST_USED_URL, url.toString())
    }

    fun updateUseAppNameAsTitle(appNameAsTitle: Boolean) {
        writeSetting(APP_NAME_AS_TITLE, appNameAsTitle.toString())
    }

    fun updateBinarySource(source: String) {
        writeSetting(BINARY_SOURCE, source)
    }

    fun updateBinaryDestination(dest: String) {
        writeSetting(BINARY_DESTINATION, dest)
    }

    fun updateDataDirectory(dir: String) {
        writeSetting(DATA_DIRECTORY, dir)
    }

    fun updateEnableDownloads(shouldEnableDownloads: Boolean) {
        writeSetting(ENABLE_DOWNLOADS, shouldEnableDownloads.toString())
    }

    fun updateDisableSignatureVerification(shouldDisableSignatureVerification: Boolean) {
        writeSetting(DISABLE_SIGNATURE_VALIDATION, shouldDisableSignatureVerification.toString())
    }

    fun updateSignatureFallbackStrategy(fallback: Boolean) {
        val strategy = when (fallback) {
            true -> SignatureFallbackStrategy.ALLOW.toString()
            else -> SignatureFallbackStrategy.FORBIDDEN.toString()
        }
        writeSetting(FALLBACK_ON_CODER_FOR_SIGNATURES, strategy)
    }

    fun updateHttpClientLogLevel(level: HttpLoggingVerbosity?) {
        if (level == null) return
        writeSetting(HTTP_CLIENT_LOG_LEVEL, level.toString())
    }

    fun updateConnectionLogBufferSize(size: Int) {
        require(size in CONNECTION_LOG_BUFFER_SIZE_RANGE) {
            "Connection log buffer size must be between 0 and 10000"
        }
        writeSetting(CONNECTION_LOG_BUFFER_SIZE, size.toString())
    }

    fun updateHeaderCommand(cmd: String) {
        writeSetting(HEADER_COMMAND, cmd)
    }

    fun updateUseKeyring(useKeyring: Boolean) {
        writeSetting(USE_KEYRING, useKeyring.toString())
    }

    fun updateCertPath(path: String) {
        writeSetting(TLS_CERT_PATH, path)
    }

    fun updateKeyPath(path: String) {
        writeSetting(TLS_KEY_PATH, path)
    }

    fun updateCAPath(path: String) {
        writeSetting(TLS_CA_PATH, path)
    }

    fun updateAltHostname(hostname: String) {
        writeSetting(TLS_ALTERNATE_HOSTNAME, hostname)
    }

    fun updateDisableAutostart(shouldDisableAutostart: Boolean) {
        writeSetting(DISABLE_AUTOSTART, shouldDisableAutostart.toString())
    }

    fun updateSshConnectionTimeoutInSeconds(sshConnectionTimeoutInSeconds: Int) {
        writeSetting(SSH_CONNECTION_TIMEOUT_IN_SECONDS, sshConnectionTimeoutInSeconds.toString())
    }

    fun updateEnableSshWildcardConfig(enable: Boolean) {
        writeSetting(ENABLE_SSH_WILDCARD_CONFIG, enable.toString())
    }

    fun updateSshConfigPath(path: String) {
        writeSetting(SSH_CONFIG_PATH, path)
    }

    fun updateSshLogDir(path: String) {
        writeSetting(SSH_LOG_DIR, path)
    }

    fun updateNetworkInfoDir(path: String) {
        writeSetting(NETWORK_INFO_DIR, path)
    }

    fun updateSshConfigOptions(options: String) {
        writeSetting(SSH_CONFIG_OPTIONS, options)
    }

    fun updateAutoConnect(workspaceId: String, autoConnect: Boolean) {
        writeSetting("$SSH_AUTO_CONNECT_PREFIX$workspaceId", autoConnect.toString())
    }

    fun updatePreferAuthViaOAuth2(preferAuthViaOAuth2: Boolean) {
        writeSetting(PREFER_OAUTH2_IF_AVAILABLE, preferAuthViaOAuth2.toString())
    }

    private fun getDefaultGlobalDataDir(): Path {
        return when (getOS()) {
            OS.WINDOWS -> Paths.get(getWinAppData(), "coder-toolbox")
            OS.MAC -> Paths.get(getMacAppData(), "coder-toolbox")
            else -> Paths.get(getLinuxAppData(), "coder-toolbox")
        }
    }

    private fun getWinAppData(): String {
        val appDataD: String = env.get("LOCALAPPDATA")
        if (appDataD.isNotBlank()) {
            return appDataD
        }
        return "${System.getProperty("user.home")}\\AppData\\Local"
    }

    private fun getMacAppData(): String {
        val appData = env.get("HOME")
        if (appData.isNotBlank()) {
            return "$appData/Library/Application Support"
        }
        return "${System.getProperty("user.home")}/Library/Application Support"
    }

    private fun getLinuxAppData(): String {
        val appData = env.get("XDG_DATA_HOME")
        if (appData.isNotBlank()) {
            return appData
        }

        val home = env.get("HOME")
        if (home.isNotBlank()) {
            return "$home/.local/share"
        }

        return "${System.getProperty("user.home")}/.local/share"
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
                Paths.get(env.get("HOME"), ".config/coderv2")
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
