package com.coder.toolbox.browser

import com.coder.toolbox.util.toURL
import com.jetbrains.toolbox.api.core.os.LocalDesktopManager


suspend fun LocalDesktopManager.browse(rawUrl: String, errorHandler: suspend (BrowserException) -> Unit) {
    try {
        val url = rawUrl.toURL()
        this.openUrl(url)
    } catch (e: Exception) {
        errorHandler(
            BrowserException(
                "Failed to open $rawUrl because an error was encountered",
                e
            )
        )
    }
}