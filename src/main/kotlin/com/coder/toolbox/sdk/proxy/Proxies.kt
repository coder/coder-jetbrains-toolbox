package com.coder.toolbox.sdk.proxy

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.remoteDev.connection.ProxyAuth
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * Applies the user's Toolbox proxy and proxy authentication settings to this builder.
 *
 * Note: This handles only HTTP/HTTPS proxy authentication. SOCKS5 proxy authentication is currently
 * not supported due to limitations described in:
 * https://youtrack.jetbrains.com/issue/TBX-14532/Missing-proxy-authentication-settings#focus=Comments-27-12265861.0-0
 */
fun OkHttpClient.Builder.applyProxySettings(context: CoderToolboxContext): OkHttpClient.Builder {
    context.proxySettings.getProxy()?.let { proxy ->
        context.logger.info("proxy: $proxy")
        proxy(proxy)
    } ?: context.proxySettings.getProxySelector()?.let { proxySelector ->
        context.logger.info("proxy selector: $proxySelector")
        proxySelector(proxySelector)
    }

    proxyAuthenticator { _, response ->
        val proxyAuth = context.proxySettings.getProxyAuth()
        if (proxyAuth == null || proxyAuth !is ProxyAuth.Basic) {
            return@proxyAuthenticator null
        }
        val credentials = Credentials.basic(proxyAuth.username, proxyAuth.password)
        response.request.newBuilder()
            .header("Proxy-Authorization", credentials)
            .build()
    }

    return this
}
