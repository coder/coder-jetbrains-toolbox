package com.coder.toolbox.cli

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.downloader.CoderDownloadApi
import com.coder.toolbox.cli.downloader.CoderDownloadService
import com.coder.toolbox.cli.downloader.DownloadResult
import com.coder.toolbox.cli.downloader.DownloadResult.Downloaded
import com.coder.toolbox.cli.ex.MissingVersionException
import com.coder.toolbox.cli.ex.SSHConfigFormatException
import com.coder.toolbox.cli.ex.UnsignedBinaryExecutionDeniedException
import com.coder.toolbox.cli.gpg.GPGVerifier
import com.coder.toolbox.cli.gpg.VerificationResult
import com.coder.toolbox.cli.gpg.VerificationResult.Failed
import com.coder.toolbox.cli.gpg.VerificationResult.Invalid
import com.coder.toolbox.sdk.CoderHttpClientBuilder
import com.coder.toolbox.settings.SignatureFallbackStrategy.ALLOW
import com.coder.toolbox.util.InvalidVersionException
import com.coder.toolbox.util.SemVer
import com.coder.toolbox.util.safeHost
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.zeroturnaround.exec.ProcessExecutor
import retrofit2.Retrofit
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/**
 * Version output from the CLI's version command.
 */
@JsonClass(generateAdapter = true)
internal data class Version(
    @Json(name = "version") val version: String,
)


/**
 * Best effort to get an up-to-date CLI.
 *
 * 1. Create a CLI manager for the deployment URL.
 * 2. If the CLI version matches the build version, return it immediately.
 * 3. Otherwise, if downloads are enabled, attempt to download the CLI.
 *    a. On success, return the CLI.
 *    b. Any exception propagates to the user.
 * 4. If downloads are disabled:
 *    a. [IllegalStateException] is raised if the CLI does not exist (look into binary destination if it was configured,
 *    fallback to data dir otherwise)
 *    b. Otherwise, warn the user and return the mismatched version.
 */
suspend fun ensureCLI(
    context: CoderToolboxContext,
    deploymentURL: URL,
    buildVersion: String,
    showTextProgress: (String) -> Unit
): CoderCLIManager {
    fun reportProgress(msg: String) {
        showTextProgress(msg)
        context.logger.info(msg)
    }

    val settings = context.settingsStore.readOnly()
    val cli = CoderCLIManager(context, deploymentURL)

    // Short-circuit if we already have the expected version.  This
    // lets us bypass the 304 which is slower and may not be
    // supported if the binary is downloaded from alternate sources.
    // For CLIs without the JSON output flag we will fall back to
    // the 304 method.
    val cliMatches = cli.matchesVersion(buildVersion)
    if (cliMatches == true) {
        reportProgress("Local CLI version matches server version: $buildVersion")
        return cli
    }

    // If downloads are enabled download the new version.
    if (settings.enableDownloads) {
        reportProgress("Downloading Coder CLI...")
        cli.download(buildVersion, showTextProgress)
        return cli
    }

    if (cliMatches == null) {
        throw IllegalStateException("Can't resolve Coder CLI and downloads are disabled")
    }
    reportProgress("Downloads are disabled, and a cached CLI is used which does not match the server version $buildVersion and could cause compatibility issues")
    return cli
}

/**
 * The supported features of the CLI.
 */
data class Features(
    val disableAutostart: Boolean = false,
    val reportWorkspaceUsage: Boolean = false,
    val wildcardSsh: Boolean = false,
    val buildReason: Boolean = false,
)

/**
 * Manage the CLI for a single deployment.
 */
class CoderCLIManager(
    private val context: CoderToolboxContext,
    // The URL of the deployment this CLI is for.
    private val deploymentURL: URL
) {
    private val downloader = createDownloadService()
    private val gpgVerifier = GPGVerifier(context)

    val remoteBinaryURL: URL = context.settingsStore.binSource(deploymentURL)
    val localBinaryPath: Path = context.settingsStore.binPath(deploymentURL)
    val coderConfigPath: Path = context.settingsStore.dataDir(deploymentURL).resolve("config")

    private fun createDownloadService(): CoderDownloadService {

        val okHttpClient = CoderHttpClientBuilder.default(context)

        val retrofit = Retrofit.Builder()
            .baseUrl(deploymentURL.toString())
            .client(okHttpClient)
            .build()

        val service = retrofit.create(CoderDownloadApi::class.java)
        return CoderDownloadService(context, service, deploymentURL)
    }

    /**
     * Download the CLI from the deployment if necessary.
     */
    suspend fun download(buildVersion: String, showTextProgress: (String) -> Unit): Boolean {
        try {
            val cliResult = withContext(Dispatchers.IO) {
                downloader.downloadCli(buildVersion, showTextProgress)
            }.let { result ->
                when {
                    result.isSkipped() -> return false
                    result.isNotFound() -> throw IllegalStateException("Could not find Coder CLI")
                    result.isFailed() -> throw (result as DownloadResult.Failed).error
                    else -> result as Downloaded
                }
            }

            if (context.settingsStore.disableSignatureVerification) {
                downloader.commit()
                context.logger.info("Skipping over CLI signature verification, it is disabled by the user")
                return true
            }

            var signatureResult = withContext(Dispatchers.IO) {
                downloader.downloadSignature(showTextProgress)
            }

            if (signatureResult.isNotDownloaded()) {
                if (context.settingsStore.fallbackOnCoderForSignatures == ALLOW) {
                    context.logger.info("Trying to download signature file from releases.coder.com")
                    signatureResult = withContext(Dispatchers.IO) {
                        downloader.downloadReleasesSignature(buildVersion, showTextProgress)
                    }

                    // if we could still not download it, ask the user if he accepts the risk
                    if (signatureResult.isNotDownloaded()) {
                        val acceptsUnsignedBinary = context.ui.showYesNoPopup(
                            context.i18n.ptrl("Security Warning"),
                            context.i18n.pnotr("Could not fetch any signatures for ${cliResult.source} from releases.coder.com. Would you like to run it anyway?"),
                            context.i18n.ptrl("Accept"),
                            context.i18n.ptrl("Abort"),
                        )

                        if (acceptsUnsignedBinary) {
                            downloader.commit()
                            return true
                        } else {
                            throw UnsignedBinaryExecutionDeniedException("Running unsigned CLI from ${cliResult.source} was denied by the user")
                        }
                    }
                } else {
                    // we are not allowed to fetch signatures from releases.coder.com
                    // so we will ask the user if he wants to continue
                    val acceptsUnsignedBinary = context.ui.showYesNoPopup(
                        context.i18n.ptrl("Security Warning"),
                        context.i18n.pnotr("No signatures were found for ${cliResult.source} and fallback to releases.coder.com is not allowed. Would you like to run it anyway?"),
                        context.i18n.ptrl("Accept"),
                        context.i18n.ptrl("Abort"),
                    )

                    if (acceptsUnsignedBinary) {
                        downloader.commit()
                        return true
                    } else {
                        throw UnsignedBinaryExecutionDeniedException("Running unsigned CLI from ${cliResult.source} was denied by the user")
                    }
                }
            }

            // we have the cli, and signature is downloaded, let's verify the signature
            signatureResult = signatureResult as Downloaded
            gpgVerifier.verifySignature(cliResult.dst, signatureResult.dst).let { result ->
                when {
                    result.isValid() -> {
                        downloader.commit()
                        return true
                    }

                    else -> {
                        logFailure(result, cliResult, signatureResult)
                        // prompt the user if he wants to accept the risk
                        val shouldRunAnyway = context.ui.showYesNoPopup(
                            context.i18n.ptrl("Security Warning"),
                            context.i18n.pnotr("Could not verify the authenticity of the ${cliResult.source}, it may be tampered with. Would you like to run it anyway?"),
                            context.i18n.ptrl("Run anyway"),
                            context.i18n.ptrl("Abort"),
                        )

                        if (shouldRunAnyway) {
                            downloader.commit()
                            return true
                        } else {
                            throw UnsignedBinaryExecutionDeniedException("Running unverified CLI from ${cliResult.source} was denied by the user")
                        }
                    }
                }
            }
        } finally {
            downloader.cleanup()
        }
    }

    private fun logFailure(
        result: VerificationResult,
        cliResult: Downloaded,
        signatureResult: Downloaded
    ) {
        when {
            result.isInvalid() -> {
                val reason = (result as Invalid).reason
                context.logger.error("Signature of ${cliResult.dst} is invalid." + reason?.let { " Reason: $it" }
                    .orEmpty())
            }

            result.signatureIsNotFound() -> {
                context.logger.error("Can't verify signature of ${cliResult.dst} because ${signatureResult.dst} does not exist")
            }

            else -> {
                val failure = result as Failed
                UnsignedBinaryExecutionDeniedException(result.error.message)
                context.logger.error(failure.error, "Failed to verify signature for ${cliResult.dst}")
            }
        }
    }

    /**
     * Use the provided token to initializeSession the CLI.
     */
    fun login(token: String): String {
        context.logger.info("Storing CLI credentials in $coderConfigPath")
        return exec(
            "login",
            deploymentURL.toString(),
            "--token",
            token,
            "--global-config",
            coderConfigPath.toString(),
        )
    }

    /**
     * Start a workspace. Throws if the command execution fails.
     */
    internal fun startWorkspace(wsAddress: WorkspaceAddress, feats: Features = features): String {
        val args = mutableListOf(
            "--global-config",
            coderConfigPath.toString(),
            "start",
            "--yes",
        )

        if (feats.buildReason) {
            args.addAll(listOf("--reason", "jetbrains_connection"))
        }
        args.add("--")
        args.add(wsAddress.ownerAndWsName)

        return exec(*args.toTypedArray())
    }

    /**
     * Configure SSH to use this binary.
     *
     * This can take supported features for testing purposes only.
     */
    internal fun configSsh(
        workspaceAddresses: Set<WorkspaceAddress>,
        feats: Features = features,
    ) {
        context.logger.info("Configuring SSH config at ${context.settingsStore.sshConfigPath}")
        writeSSHConfig(modifySSHConfig(readSSHConfig(), workspaceAddresses, feats))
        context.logger.info("Finished configuring SSH config")
    }

    /**
     * Return the contents of the SSH config or null if it does not exist.
     */
    private fun readSSHConfig(): String? = try {
        Path.of(context.settingsStore.sshConfigPath).toFile().readText()
    } catch (_: FileNotFoundException) {
        null
    }

    /**
     * Given an existing SSH config modify it to add or remove the config for
     * this deployment and return the modified config or null if it does not
     * need to be modified.
     *
     * If features are not provided, calculate them based on the binary
     * version.
     */
    private fun modifySSHConfig(
        contents: String?,
        workspaceAddresses: Set<WorkspaceAddress>,
        feats: Features,
    ): String? {
        val host = deploymentURL.safeHost()
        val startBlock = "# --- START CODER JETBRAINS TOOLBOX $host"
        val endBlock = "# --- END CODER JETBRAINS TOOLBOX $host"
        val isRemoving = workspaceAddresses.isEmpty()
        val baseArgs =
            listOfNotNull(
                localBinaryPath.toString(),
                "--global-config",
                coderConfigPath.toString(),
                // CODER_URL might be set, and it will override the URL file in
                // the config directory, so override that here to make sure we
                // always use the correct URL.
                "--url",
                deploymentURL.toString(),
                context.settingsStore.headerCommand?.takeIf { it.isNotBlank() }?.let { "--header-command" },
                context.settingsStore.headerCommand?.takeIf { it.isNotBlank() },
                "ssh",
                "--stdio",
                if (context.settingsStore.disableAutostart && feats.disableAutostart) "--disable-autostart" else null,
                "--network-info-dir",
                context.settingsStore.networkInfoDir,
            )
        val proxyArgs = buildList {
            addAll(baseArgs)
            context.settingsStore.sshLogDirectory?.takeIf { it.isNotBlank() }?.let {
                add("--log-dir")
                add(it)
                add("-v")
            }
            if (feats.reportWorkspaceUsage) add("--usage-app=jetbrains")
        }
        val extraConfig = context.settingsStore.sshConfigOptions
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n" + it.prependIndent("  ") }
            ?: ""
        val options = """
            ConnectTimeout ${context.settingsStore.sshConnectionTimeoutInSeconds}
            StrictHostKeyChecking no
            UserKnownHostsFile /dev/null
            LogLevel ERROR
            SetEnv CODER_SSH_SESSION_TYPE=JetBrains
        """.trimIndent()

        val blockContent = if (context.settingsStore.isSshWildcardConfigEnabled && feats.wildcardSsh) {
            val hostnamePrefix = WorkspaceAddress.wildcardSshHostPrefix(deploymentURL.safeHost())
            val proxyCommand = ProxyCommandBuilder()
                .arguments(proxyArgs)
                .argument("--ssh-host-prefix")
                .argument("$hostnamePrefix--")
                .argument("--")
                .sshToken("%h")
                .render()
            startBlock + System.lineSeparator() +
                    """
                    Host $hostnamePrefix--*
                      ProxyCommand $proxyCommand
                    """.trimIndent()
                        .plus("\n" + options.prependIndent("  "))
                        .plus(extraConfig)
                        .plus("\n")
                        .replace("\n", System.lineSeparator()) +
                    System.lineSeparator() + endBlock
        } else {
            workspaceAddresses.joinToString(
                System.lineSeparator(),
                startBlock + System.lineSeparator(),
                System.lineSeparator() + endBlock,
                transform = { workspaceAddress ->
                    val proxyCommand = ProxyCommandBuilder()
                        .arguments(proxyArgs)
                        .argument("--")
                        .argument(workspaceAddress.ownerWsAndAgentName)
                        .render()
                    """
                    Host ${getHostname(deploymentURL, workspaceAddress)}
                      ProxyCommand $proxyCommand
                    """.trimIndent()
                        .plus("\n" + options.prependIndent("  "))
                        .plus(extraConfig)
                        .plus("\n")
                        .replace("\n", System.lineSeparator())
                },
            )
        }

        if (contents == null) {
            context.logger.info("No existing SSH config to modify")
            return blockContent + System.lineSeparator()
        }

        val managedBlock = findManagedBlock(contents, startBlock, endBlock)

        if (managedBlock == null && isRemoving) {
            context.logger.info("No workspaces and no existing config blocks to remove")
            return null
        }

        if (managedBlock == null) {
            context.logger.info("Appending config block")
            val toAppend =
                if (contents.isEmpty()) {
                    blockContent
                } else {
                    listOf(
                        contents,
                        blockContent,
                    ).joinToString(System.lineSeparator())
                }
            return toAppend + System.lineSeparator()
        }

        val (start, end) = managedBlock

        if (isRemoving) {
            context.logger.info("No workspaces; removing config block")
            return listOf(
                contents.substring(0, start.range.first),
                // Need to keep the trailing newline(s) if we are not at the
                // front of the file otherwise the before and after lines would
                // get joined.
                if (start.range.first > 0) end.groupValues[1] else "",
                contents.substring(end.range.last + 1),
            ).joinToString("")
        }

        context.logger.info("Replacing existing config block")
        return listOf(
            contents.substring(0, start.range.first),
            start.groupValues[1], // Leading newline(s).
            blockContent,
            end.groupValues[1], // Trailing newline(s).
            contents.substring(end.range.last + 1),
        ).joinToString("")
    }

    /**
     * Locate a managed block, using the final end marker so a rewrite also
     * removes content following an end marker injected by a vulnerable version.
     */
    private fun findManagedBlock(contents: String, startMarker: String, endMarker: String): ManagedBlock? {
        val start = "(\\s*)${Regex.escape(startMarker)}".toRegex().find(contents)
        val allEnds = "${Regex.escape(endMarker)}(\\s*)".toRegex().findAll(contents).toList()

        if (start == null && allEnds.isEmpty()) return null
        if (start == null) throw SSHConfigFormatException("End block exists but no start block")

        val end = allEnds.lastOrNull { it.range.first > start.range.first }
            ?: if (allEnds.isEmpty()) {
                throw SSHConfigFormatException("Start block exists but no end block")
            } else {
                throw SSHConfigFormatException("Start block found after end block")
            }
        return ManagedBlock(start, end)
    }

    /**
     * Write the provided SSH config or do nothing if null.
     */
    private fun writeSSHConfig(contents: String?) {
        if (contents != null) {
            if (context.settingsStore.sshConfigPath.isNotBlank()) {
                val sshConfPath = Path.of(context.settingsStore.sshConfigPath)
                sshConfPath.parent.toFile().mkdirs()
                sshConfPath.toFile().writeText(contents)
            }
            // The Coder cli will *not* create the log directory.
            if (!context.settingsStore.sshLogDirectory.isNullOrBlank()) {
                Path.of(context.settingsStore.sshLogDirectory).toFile().mkdirs()
            }
        }
    }

    /**
     * Return the binary version.
     *
     * Throws if it could not be determined.
     */
    fun version(): SemVer {
        val raw = exec("version", "--output", "json")
        try {
            val json = Moshi.Builder().build().adapter(Version::class.java).fromJson(raw)
            if (json?.version == null || json.version.isBlank()) {
                throw MissingVersionException("No version found in output")
            }
            return SemVer.parse(json.version)
        } catch (_: JsonDataException) {
            throw MissingVersionException("No version found in output")
        } catch (_: EOFException) {
            throw MissingVersionException("No version found in output")
        }
    }

    /**
     * Like version(), but logs errors instead of throwing them.
     */
    private fun tryVersion(): SemVer? = try {
        version()
    } catch (e: Exception) {
        when (e) {
            is InvalidVersionException -> {
                context.logger.info("Got invalid version from $localBinaryPath: ${e.message}")
            }

            else -> {
                // An error here most likely means the CLI does not exist, or
                // it executed successfully but output no version which
                // suggests it is not the right binary.
                context.logger.info("Unable to determine $localBinaryPath version: ${e.message}")
            }
        }
        null
    }

    /**
     * Returns true if the CLI has the same major/minor/patch version as the
     * provided version, false if it does not match, or null if the CLI version
     * could not be determined because the binary could not be executed or the
     * version could not be parsed.
     */
    fun matchesVersion(rawBuildVersion: String): Boolean? {
        if (Files.notExists(localBinaryPath)) return null
        val cliVersion = tryVersion() ?: return null
        val buildVersion =
            try {
                SemVer.parse(rawBuildVersion)
            } catch (_: InvalidVersionException) {
                context.logger.info("Got invalid build version: $rawBuildVersion")
                return null
            }

        val matches = cliVersion == buildVersion
        context.logger.info("$localBinaryPath version $cliVersion matches $buildVersion: $matches")
        return matches
    }

    private fun exec(vararg args: String): String {
        val stdout =
            ProcessExecutor()
                .command(localBinaryPath.toString(), *args)
                .environment("CODER_HEADER_COMMAND", context.settingsStore.headerCommand)
                .exitValues(0)
                .readOutput(true)
                .execute()
                .outputUTF8()
        val redactedArgs = listOf(*args).joinToString(" ").replace(tokenRegex, "--token <redacted>")
        context.logger.info("`$localBinaryPath $redactedArgs`: $stdout")
        return stdout
    }

    val features: Features
        get() {
            val version = tryVersion()
            return if (version == null) {
                Features()
            } else {
                Features(
                    disableAutostart = version >= SemVer(2, 5, 0),
                    reportWorkspaceUsage = version >= SemVer(2, 13, 0),
                    wildcardSsh = version >= SemVer(2, 19, 0),
                    buildReason = version >= SemVer(2, 25, 0),
                )
            }
        }

    internal fun getHostname(url: URL, workspaceAddress: WorkspaceAddress): String {
        return if (context.settingsStore.isSshWildcardConfigEnabled && features.wildcardSsh) {
            workspaceAddress.wildcardSshHostAlias(url.safeHost())
        } else {
            workspaceAddress.sshHostAlias(url.safeHost())
        }
    }

    companion object {
        private data class ManagedBlock(val start: MatchResult, val end: MatchResult)

        private val tokenRegex = "--token [^ ]+".toRegex()
    }
}
