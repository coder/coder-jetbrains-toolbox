package com.coder.toolbox.cli.downloader

/**
 * Result of a download operation
 */
sealed class DownloadResult {
    object Skipped : DownloadResult()
    object Downloaded : DownloadResult()
    data class Failed(val error: Exception) : DownloadResult()
}