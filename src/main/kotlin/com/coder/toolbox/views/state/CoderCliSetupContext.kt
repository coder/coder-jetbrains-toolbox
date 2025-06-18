package com.coder.toolbox.views.state

import java.net.URL

/**
 * Singleton that holds Coder CLI setup context (URL and token) across multiple
 * Toolbox window lifecycle events.
 *
 * This ensures that user input (URL and token) is not lost when the Toolbox
 * window is temporarily closed or recreated.
 */
object CoderCliSetupContext {
    /**
     * The currently entered URL.
     */
    var url: URL? = null

    /**
     * The token associated with the URL.
     */
    var token: String? = null

    /**
     * Returns true if a URL is currently set.
     */
    fun hasUrl(): Boolean = url != null

    /**
     * Returns true if a token is currently set.
     */
    fun hasToken(): Boolean = !token.isNullOrBlank()

    /**
     * Returns true if URL or token is missing and auth is not yet possible.
     */
    fun isNotReadyForAuth(): Boolean = !(hasUrl() && token != null)

    /**
     * Resets both URL and token to null.
     */
    fun reset() {
        url = null
        token = null
    }
}