package com.coder.toolbox.sdk

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.util.CoderHostnameVerifier
import com.coder.toolbox.util.ReloadableTlsContext
import com.jetbrains.toolbox.api.remoteDev.connection.ProxyAuth
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient

object CoderHttpClientBuilder {
    fun build(
        context: CoderToolboxContext,
        interceptors: List<Interceptor>,
        tlsContext: ReloadableTlsContext
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()

        context.proxySettings.getProxy()?.let { proxy ->
            context.logger.info("proxy: $proxy")
            builder.proxy(proxy)
        } ?: context.proxySettings.getProxySelector()?.let { proxySelector ->
            context.logger.info("proxy selector: $proxySelector")
            builder.proxySelector(proxySelector)
        }

        // Note: This handles only HTTP/HTTPS proxy authentication.
        // SOCKS5 proxy authentication is currently not supported due to limitations described in:
        // https://youtrack.jetbrains.com/issue/TBX-14532/Missing-proxy-authentication-settings#focus=Comments-27-12265861.0-0
        builder.proxyAuthenticator { _, response ->
            val proxyAuth = context.proxySettings.getProxyAuth()
            if (proxyAuth == null || proxyAuth !is ProxyAuth.Basic) {
                return@proxyAuthenticator null
            }
            val credentials = Credentials.basic(proxyAuth.username, proxyAuth.password)
            response.request.newBuilder()
                .header("Proxy-Authorization", credentials)
                .build()
        }

        builder.sslSocketFactory(tlsContext.sslSocketFactory, tlsContext.trustManager)
            .hostnameVerifier(CoderHostnameVerifier(context.settingsStore.tls.altHostname))
            .retryOnConnectionFailure(true)

        interceptors.forEach { interceptor ->
            builder.addInterceptor(interceptor)

        }
        return builder.build()
    }
}