package com.coder.toolbox.cli.downloader

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.ex.ResponseException
import com.coder.toolbox.util.OS
import com.coder.toolbox.util.SemVer
import com.coder.toolbox.util.getHeaders
import com.coder.toolbox.util.getOS
import com.coder.toolbox.util.sha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.FileInputStream
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import kotlin.io.path.name
import kotlin.io.path.notExists

private val SUPPORTED_BIN_MIME_TYPES = listOf(
    "application/octet-stream",
    "application/exe",
    "application/dos-exe",
    "application/msdos-windows",
    "application/x-exe",
    "application/x-msdownload",
    "application/x-winexe",
    "application/x-msdos-program",
    "application/x-msdos-executable",
    "application/x-ms-dos-executable",
    "application/vnd.microsoft.portable-executable"
)
/**
 * Handles the download steps of Coder CLI
 */
class CoderDownloadService(
    private val context: CoderToolboxContext,
    private val downloadApi: CoderDownloadApi,
    private val deploymentUrl: URL,
    forceDownloadToData: Boolean,
) {
    private val remoteBinaryURL: URL = context.settingsStore.binSource(deploymentUrl)
    private val cliFinalDst: Path = context.settingsStore.binPath(deploymentUrl, forceDownloadToData)
    private val cliTempDst: Path = cliFinalDst.resolveSibling("${cliFinalDst.name}.tmp")

    suspend fun downloadCli(buildVersion: String, showTextProgress: (String) -> Unit): DownloadResult {
        val eTag = calculateLocalETag()
        if (eTag != null) {
            context.logger.info("Found existing binary at $cliFinalDst; calculated hash as $eTag")
        }
        val response = downloadApi.downloadCli(
            url = remoteBinaryURL.toString(),
            eTag = eTag?.let { "\"$it\"" },
            headers = getRequestHeaders()
        )

        return when (response.code()) {
            HTTP_OK -> {
                val contentType = response.headers()["Content-Type"]?.lowercase()
                if (contentType !in SUPPORTED_BIN_MIME_TYPES) {
                    throw ResponseException(
                        "Invalid content type '$contentType' when downloading CLI from $remoteBinaryURL. Expected application/octet-stream.",
                        response.code()
                    )
                }
                context.logger.info("Downloading binary to temporary $cliTempDst")
                response.saveToDisk(cliTempDst, showTextProgress, buildVersion)?.makeExecutable()
                DownloadResult.Downloaded(remoteBinaryURL, cliTempDst)
            }

            HTTP_NOT_MODIFIED -> {
                context.logger.info("Using cached binary at $cliFinalDst")
                showTextProgress("Using cached binary")
                DownloadResult.Skipped
            }

            else -> {
                throw ResponseException(
                    "Unexpected response from $remoteBinaryURL",
                    response.code()
                )
            }
        }
    }

    /**
     * Renames the temporary binary file to its original destination name.
     * The implementation will override sibling file that has the original
     * destination name.
     */
    suspend fun commit(): Path {
        return withContext(Dispatchers.IO) {
            context.logger.info("Renaming binary from $cliTempDst to $cliFinalDst")
            Files.move(cliTempDst, cliFinalDst, StandardCopyOption.REPLACE_EXISTING)
            cliFinalDst.makeExecutable()
            cliFinalDst
        }
    }

    /**
     * Cleans up the temporary binary file if it exists.
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            runCatching { Files.deleteIfExists(cliTempDst) }
                .onFailure { ex ->
                    context.logger.warn(ex, "Failed to delete temporary CLI file: $cliTempDst")
                }
        }
    }

    private fun calculateLocalETag(): String? {
        return try {
            if (cliFinalDst.notExists()) {
                return null
            }
            sha1(FileInputStream(cliFinalDst.toFile()))
        } catch (e: Exception) {
            context.logger.warn(e, "Unable to calculate hash for $cliFinalDst")
            null
        }
    }

    private fun getRequestHeaders(): Map<String, String> {
        return if (context.settingsStore.headerCommand.isNullOrBlank()) {
            emptyMap()
        } else {
            getHeaders(deploymentUrl, context.settingsStore.headerCommand)
        }
    }

    private fun Response<ResponseBody>.saveToDisk(
        localPath: Path,
        showTextProgress: (String) -> Unit,
        buildVersion: String? = null
    ): Path? {
        val responseBody = this.body() ?: return null
        Files.deleteIfExists(localPath)
        Files.createDirectories(localPath.parent)

        val outputStream = Files.newOutputStream(
            localPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        val contentEncoding = this.headers()["Content-Encoding"]
        val sourceStream = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(responseBody.byteStream())
        } else {
            responseBody.byteStream()
        }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead: Int
        var totalRead = 0L
        // local path is a temporary filename, reporting the progress with the real name
        val binaryName = localPath.name.removeSuffix(".tmp")
        sourceStream.use { source ->
            outputStream.use { sink ->
                while (source.read(buffer).also { bytesRead = it } != -1) {
                    sink.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val prettyBuildVersion = buildVersion ?: ""
                    showTextProgress(
                        "$binaryName $prettyBuildVersion - ${totalRead.toHumanReadableSize()} downloaded"
                    )
                }
            }
        }
        return cliFinalDst
    }


    private fun Path.makeExecutable() {
        if (getOS() != OS.WINDOWS) {
            context.logger.info("Making $this executable...")
            this.toFile().setExecutable(true)
        }
    }

    private fun Long.toHumanReadableSize(): String {
        if (this < 1024) return "$this B"

        val kb = this / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)

        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)

        val gb = mb / 1024.0
        return String.format("%.1f GB", gb)
    }

    suspend fun downloadSignature(showTextProgress: (String) -> Unit): DownloadResult {
        return downloadSignature(remoteBinaryURL, showTextProgress, getRequestHeaders())
    }

    private suspend fun downloadSignature(
        url: URL,
        showTextProgress: (String) -> Unit,
        headers: Map<String, String> = emptyMap()
    ): DownloadResult {
        val signatureURL = url.toURI().resolve(context.settingsStore.defaultSignatureNameByOsAndArch).toURL()
        val localSignaturePath = cliFinalDst.parent.resolve(context.settingsStore.defaultSignatureNameByOsAndArch)
        context.logger.info("Downloading signature from $signatureURL")

        val response = downloadApi.downloadSignature(
            url = signatureURL.toString(),
            headers = headers
        )

        return when (response.code()) {
            HTTP_OK -> {
                response.saveToDisk(localSignaturePath, showTextProgress)
                DownloadResult.Downloaded(signatureURL, localSignaturePath)
            }

            HTTP_NOT_FOUND -> {
                context.logger.warn("Signature file not found at $signatureURL")
                DownloadResult.NotFound
            }

            else -> {
                DownloadResult.Failed(
                    ResponseException(
                        "Failed to download signature from $signatureURL",
                        response.code()
                    )
                )
            }
        }

    }

    suspend fun downloadReleasesSignature(
        buildVersion: String,
        showTextProgress: (String) -> Unit
    ): DownloadResult {
        val semVer = SemVer.parse(buildVersion)
        return downloadSignature(
            URI.create("https://releases.coder.com/coder-cli/${semVer.major}.${semVer.minor}.${semVer.patch}/").toURL(),
            showTextProgress
        )
    }
}