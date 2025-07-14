package com.coder.toolbox.cli.downloader

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.ex.ResponseException
import com.coder.toolbox.util.OS
import com.coder.toolbox.util.SemVer
import com.coder.toolbox.util.getHeaders
import com.coder.toolbox.util.getOS
import com.coder.toolbox.util.sha1
import com.coder.toolbox.util.withLastSegment
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
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import kotlin.io.path.name
import kotlin.io.path.notExists

/**
 * Handles the download steps of Coder CLI
 */
class CoderDownloadService(
    private val context: CoderToolboxContext,
    private val downloadApi: CoderDownloadApi,
    private val deploymentUrl: URL,
    forceDownloadToData: Boolean,
) {
    val remoteBinaryURL: URL = context.settingsStore.binSource(deploymentUrl)
    val localBinaryPath: Path = context.settingsStore.binPath(deploymentUrl, forceDownloadToData)

    suspend fun downloadCli(buildVersion: String, showTextProgress: (String) -> Unit): DownloadResult {
        val eTag = calculateLocalETag()
        if (eTag != null) {
            context.logger.info("Found existing binary at $localBinaryPath; calculated hash as $eTag")
        }
        val response = downloadApi.downloadCli(
            url = remoteBinaryURL.toString(),
            eTag = eTag?.let { "\"$it\"" },
            headers = getRequestHeaders()
        )

        return when (response.code()) {
            HTTP_OK -> {
                context.logger.info("Downloading binary to $localBinaryPath")
                response.saveToDisk(localBinaryPath, showTextProgress, buildVersion)?.makeExecutable()
                DownloadResult.Downloaded(remoteBinaryURL, localBinaryPath)
            }

            HTTP_NOT_MODIFIED -> {
                context.logger.info("Using cached binary at $localBinaryPath")
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

    private fun calculateLocalETag(): String? {
        return try {
            if (localBinaryPath.notExists()) {
                return null
            }
            sha1(FileInputStream(localBinaryPath.toFile()))
        } catch (e: Exception) {
            context.logger.warn(e, "Unable to calculate hash for $localBinaryPath")
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
        // caching this because the settings store recomputes it every time
        val binaryName = localPath.name
        sourceStream.use { source ->
            outputStream.use { sink ->
                while (source.read(buffer).also { bytesRead = it } != -1) {
                    sink.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    showTextProgress(
                        "$binaryName $buildVersion - ${totalRead.toHumanReadableSize()} downloaded"
                    )
                }
            }
        }
        return localBinaryPath
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
        return downloadSignature(remoteBinaryURL, showTextProgress)
    }

    private suspend fun downloadSignature(url: URL, showTextProgress: (String) -> Unit): DownloadResult {
        val defaultCliNameWithoutExt = context.settingsStore.defaultCliBinaryNameByOsAndArch.split('.').first()
        val signatureName = "$defaultCliNameWithoutExt.asc"

        val signatureURL = url.withLastSegment(signatureName)
        val localSignaturePath = localBinaryPath.parent.resolve(signatureName)
        context.logger.info("Downloading signature from $signatureURL")

        val response = downloadApi.downloadSignature(
            url = signatureURL.toString(),
            headers = getRequestHeaders()
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

    suspend fun downloadReleasesSignature(buildVersion: String, showTextProgress: (String) -> Unit): DownloadResult {
        val semVer = SemVer.parse(buildVersion)
        return downloadSignature(
            URI.create("https://releases.coder.com/coder-cli/${semVer.major}.${semVer.minor}.${semVer.patch}/").toURL(),
            showTextProgress
        )
    }
}