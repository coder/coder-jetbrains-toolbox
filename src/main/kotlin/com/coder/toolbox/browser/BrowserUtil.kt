package com.coder.toolbox.browser

import com.jetbrains.toolbox.api.core.os.LocalDesktopManager
import java.net.URI


suspend fun LocalDesktopManager.browse(rawUrl: String, errorHandler: suspend (BrowserException) -> Unit) {
    try {
        val url = URI.create(rawUrl).toURL()
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