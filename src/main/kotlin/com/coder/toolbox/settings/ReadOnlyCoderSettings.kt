package com.coder.toolbox.settings

import java.net.URL
import java.nio.file.Path
import java.util.Locale.getDefault

/**
 * Read-only interface for accessing Coder settings
 */
interface ReadOnlyCoderSettings {
    /**
     * The default URL to show in the connection window.
     */
    val defaultURL: String

    /**
     * Used to download the Coder CLI which is necessary to proxy SSH
     * connections.  The If-None-Match header will be set to the SHA1 of the CLI
     * and can be used for caching.  Absolute URLs will be used as-is; otherwise
     * this value will be resolved against the deployment domain.  Defaults to
     * the plugin's data directory.
     */
    val binarySource: String?

    /**
     * Directories are created here that store the CLI for each domain to which
     * the plugin connects.   Defaults to the data directory.
     */
    val binaryDirectory: String?

    /**
     * Controls whether we verify the cli signature
     */
    val disableSignatureVerification: Boolean

    /**
     * Controls whether we fall back on release.coder.com for signatures if signature validation is enabled
     */
    val fallbackOnCoderForSignatures: SignatureFallbackStrategy

    /**
     * Controls the logging for the rest client.
     */
    val httpClientLogLevel: HttpLoggingVerbosity

    /**
     * Default CLI binary name based on OS and architecture
     */
    val defaultCliBinaryNameByOsAndArch: String

    /**
     * Configurable CLI binary name with extension, dependent on OS and arch
     */
    val binaryName: String

    /**
     * Default CLI signature name based on OS and architecture
     */
    val defaultSignatureNameByOsAndArch: String

    /**
     * Where to save plugin data like the Coder binary (if not configured with
     * binaryDirectory) and the deployment URL and session token.
     */
    val dataDirectory: String?

    /**
     * Coder plugin's global data directory.
     */
    val globalDataDirectory: String

    /**
     * Coder plugin's global config dir
     */
    val globalConfigDir: String

    /**
     * Whether to allow the plugin to download the CLI if the current one is out
     * of date or does not exist.
     */
    val enableDownloads: Boolean

    /**
     * Whether to allow the plugin to fall back to the data directory when the
     * CLI directory is not writable.
     */
    val enableBinaryDirectoryFallback: Boolean

    /**
     * An external command that outputs additional HTTP headers added to all
     * requests. The command must output each header as `key=value` on its own
     * line. The following environment variables will be available to the
     * process: CODER_URL.
     */
    val headerCommand: String?

    /**
     * Optional TLS settings
     */
    val tls: ReadOnlyTLSSettings

    /**
     * Whether login should be done with a token
     */
    val requireTokenAuth: Boolean

    /**
     * Whether to add --disable-autostart to the proxy command.  This works
     * around issues on macOS where it periodically wakes and Gateway
     * reconnects, keeping the workspace constantly up.
     */
    val disableAutostart: Boolean

    /**
     * Whether SSH wildcard config is enabled
     */
    val isSshWildcardConfigEnabled: Boolean

    /**
     * The location of the SSH config.  Defaults to ~/.ssh/config.
     */
    val sshConfigPath: String

    /**
     * Value for --log-dir.
     */
    val sshLogDirectory: String?

    /**
     * Extra SSH config options
     */
    val sshConfigOptions: String?


    /**
     * The path where network information for SSH hosts are stored
     */
    val networkInfoDir: String

    /**
     * Where the specified deployment should put its data.
     */
    fun dataDir(url: URL): Path

    /**
     * From where the specified deployment should download the binary.
     */
    fun binSource(url: URL): URL

    /**
     * To where the specified deployment should download the binary.
     */
    fun binPath(url: URL, forceDownloadToData: Boolean = false): Path

    /**
     * Return the URL and token from the config, if they exist.
     */
    fun readConfig(dir: Path): Pair<String?, String?>

    /**
     * Returns whether the SSH connection should be automatically established.
     */
    fun shouldAutoConnect(workspaceId: String): Boolean
}

/**
 * Read-only interface for TLS settings
 */
interface ReadOnlyTLSSettings {
    /**
     * Optionally set this to the path of a certificate to use for TLS
     * connections. The certificate should be in X.509 PEM format.
     */
    val certPath: String?

    /**
     * Optionally set this to the path of the private key that corresponds to
     * the above cert path to use for TLS connections. The key should be in
     * X.509 PEM format.
     */
    val keyPath: String?

    /**
     * Optionally set this to the path of a file containing certificates for an
     * alternate certificate authority used to verify TLS certs returned by the
     * Coder service. The file should be in X.509 PEM format.
     */
    val caPath: String?

    /**
     * Optionally set this to an alternate hostname used for verifying TLS
     * connections. This is useful when the hostname used to connect to the
     * Coder service does not match the hostname in the TLS certificate.
     */
    val altHostname: String?
}

enum class SignatureFallbackStrategy {
    /**
     * User has not yet decided whether he wants to fallback on releases.coder.com for signatures
     */
    NOT_CONFIGURED,

    /**
     * Can fall back on releases.coder.com for signatures.
     */
    ALLOW,

    /**
     * Can't fall back on releases.coder.com for signatures.
     */
    FORBIDDEN;

    fun isAllowed(): Boolean = this == ALLOW

    companion object {
        fun fromValue(value: String?): SignatureFallbackStrategy = when (value?.lowercase(getDefault())) {
            "not_configured" -> NOT_CONFIGURED
            "allow" -> ALLOW
            "forbidden" -> FORBIDDEN
            else -> NOT_CONFIGURED
        }
    }
}

enum class HttpLoggingVerbosity {
    NONE,

    /**
     * Logs URL, method, and status
     */
    BASIC,

    /**
     * Logs BASIC + sanitized headers
     */
    HEADERS,

    /**
     * Logs HEADERS + body content
     */
    BODY;

    companion object {
        fun fromValue(value: String?): HttpLoggingVerbosity = when (value?.lowercase(getDefault())) {
            "basic" -> BASIC
            "headers" -> HEADERS
            "body" -> BODY
            else -> NONE
        }
    }
}