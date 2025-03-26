package com.coder.toolbox.settings

import com.coder.toolbox.util.expand
import com.coder.toolbox.util.safeHost
import com.coder.toolbox.util.toURL
import com.coder.toolbox.util.withPath
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

data class CoderSettings(
    val defaultURL: String?,

    /**
     * Used to download the Coder CLI which is necessary to proxy SSH
     * connections.  The If-None-Match header will be set to the SHA1 of the CLI
     * and can be used for caching.  Absolute URLs will be used as-is; otherwise
     * this value will be resolved against the deployment domain.  Defaults to
     * the plugin's data directory.
     */
    val binarySource: String?,

    /**
     * Directories are created here that store the CLI for each domain to which
     * the plugin connects.   Defaults to the data directory.
     */
    val binaryDirectory: String?,

    val defaultCliBinaryNameByOsAndArch: String,

    /**
     * Configurable CLI binary name with extension, dependent on OS and arch
     */
    val binaryName: String,

    /**
     * Where to save plugin data like the Coder binary (if not configured with
     * binaryDirectory) and the deployment URL and session token.
     */
    val dataDirectory: String?,

    /**
     * Coder plugin's global data directory.
     */
    val globalDataDirectory: String,

    /**
     * Coder plugin's global config dir
     */
    val globalConfigDir: String,

    /**
     * Whether to allow the plugin to download the CLI if the current one is out
     * of date or does not exist.
     */
    val enableDownloads: Boolean,

    /**
     * Whether to allow the plugin to fall back to the data directory when the
     * CLI directory is not writable.
     */
    val enableBinaryDirectoryFallback: Boolean,

    /**
     * An external command that outputs additional HTTP headers added to all
     * requests. The command must output each header as `key=value` on its own
     * line. The following environment variables will be available to the
     * process: CODER_URL.
     */
    val headerCommand: String?,

    /**
     * Optional TLS settings
     */
    val tls: CTLSSettings,

    /**
     * Whether login should be done with a token
     */
    val requireTokenAuth: Boolean = tls.certPath.isNullOrBlank() || tls.keyPath.isNullOrBlank(),

    /**
     * Whether to add --disable-autostart to the proxy command.  This works
     * around issues on macOS where it periodically wakes and Gateway
     * reconnects, keeping the workspace constantly up.
     */
    val disableAutostart: Boolean,

    /**
     * The location of the SSH config.  Defaults to ~/.ssh/config.
     */
    val sshConfigPath: String,

    /**
     * Value for --log-dir.
     */
    val sshLogDirectory: String?,

    /**
     * Extra SSH config options
     */
    val sshConfigOptions: String?,
) {

    /**
     * Given a deployment URL, try to find a token for it if required.
     */
    fun token(deploymentURL: URL): Pair<String, SettingSource>? {
        // No need to bother if we do not need token auth anyway.
        if (!requireTokenAuth) {
            return null
        }
        // Try the deployment's config directory.  This could exist if someone
        // has entered a URL that they are not currently connected to, but have
        // connected to in the past.
        val (_, deploymentToken) = readConfig(dataDir(deploymentURL).resolve("config"))
        if (!deploymentToken.isNullOrBlank()) {
            return deploymentToken to SettingSource.DEPLOYMENT_CONFIG
        }
        // Try the global config directory, in case they previously set up the
        // CLI with this URL.
        val (configUrl, configToken) = readConfig(Path.of(globalConfigDir))
        if (configUrl == deploymentURL.toString() && !configToken.isNullOrBlank()) {
            return configToken to SettingSource.CONFIG
        }
        return null
    }

    /**
     * Where the specified deployment should put its data.
     */
    fun dataDir(url: URL): Path {
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
    fun binSource(url: URL): URL {
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
    fun binPath(
        url: URL,
        forceDownloadToData: Boolean = false,
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
    fun readConfig(dir: Path): Pair<String?, String?> {
//        logger.info("Reading config from $dir")
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

/**
 * Consolidated TLS settings.
 */
data class CTLSSettings(
    /**
     * Optionally set this to the path of a certificate to use for TLS
     * connections. The certificate should be in X.509 PEM format.
     */
    val certPath: String?,

    /**
     * Optionally set this to the path of the private key that corresponds to
     * the above cert path to use for TLS connections. The key should be in
     * X.509 PEM format.
     */
    val keyPath: String?,

    /**
     * Optionally set this to the path of a file containing certificates for an
     * alternate certificate authority used to verify TLS certs returned by the
     * Coder service. The file should be in X.509 PEM format.
     */
    val caPath: String?,

    /**
     * Optionally set this to an alternate hostname used for verifying TLS
     * connections. This is useful when the hostname used to connect to the
     * Coder service does not match the hostname in the TLS certificate.
     */
    val altHostname: String?,
)