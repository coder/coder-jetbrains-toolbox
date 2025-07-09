package com.coder.toolbox.cli.downloader

import java.nio.file.Path


/**
 * Result of a download operation
 */
sealed class DownloadResult {
    object Skipped : DownloadResult()
    object NotFound : DownloadResult()
    data class Downloaded(val savePath: Path) : DownloadResult()
    data class Failed(val error: Exception) : DownloadResult()

    fun isDownloaded(): Boolean = this is Downloaded
}