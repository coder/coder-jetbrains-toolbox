package com.coder.toolbox.views.state

import java.net.URL

data class AuthContext(
    var url: URL? = null,
    var token: String? = null
) {
    fun hasUrl(): Boolean = url != null

    fun isNotReadyForAuth(): Boolean = !(hasUrl() && token != null)

    fun reset() {
        url = null
        token = null
    }
}