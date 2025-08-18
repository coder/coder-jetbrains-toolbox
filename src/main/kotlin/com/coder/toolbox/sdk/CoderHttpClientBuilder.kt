package com.coder.toolbox.sdk

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.interceptors.LoggingInterceptor
import com.coder.toolbox.util.CoderHostnameVerifier
import com.coder.toolbox.util.coderSocketFactory
import com.coder.toolbox.util.coderTrustManagers
import com.coder.toolbox.util.getArch
import com.coder.toolbox.util.getHeaders
import com.coder.toolbox.util.getOS
import com.jetbrains.toolbox.api.remoteDev.connection.ProxyAuth
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.URL
import javax.net.ssl.X509TrustManager

object CoderHttpClientBuilder {
    fun build(
        context: CoderToolboxContext,
        pluginVersion: String,
        url: URL,
        token: String?,
    ): OkHttpClient {
        val settings = context.settingsStore.readOnly()

        val socketFactory = coderSocketFactory(settings.tls)
        val trustManagers = coderTrustManagers(settings.tls.caPath)
        var builder = OkHttpClient.Builder()

        if (context.proxySettings.getProxy() != null) {
            context.logger.info("proxy: ${context.proxySettings.getProxy()}")
            builder.proxy(context.proxySettings.getProxy())
        } else if (context.proxySettings.getProxySelector() != null) {
            context.logger.info("proxy selector: ${context.proxySettings.getProxySelector()}")
            builder.proxySelector(context.proxySettings.getProxySelector()!!)
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

        if (context.settingsStore.requireTokenAuth) {
            if (token.isNullOrBlank()) {
                throw IllegalStateException("Token is required for $url deployment")
            }
            builder = builder.addInterceptor {
                it.proceed(
                    it.request().newBuilder().addHeader("Coder-Session-Token", token).build()
                )
            }
        }

        return builder
            .sslSocketFactory(socketFactory, trustManagers[0] as X509TrustManager)
            .hostnameVerifier(CoderHostnameVerifier(settings.tls.altHostname))
            .retryOnConnectionFailure(true)
            .addInterceptor {
                it.proceed(
                    it.request().newBuilder().addHeader(
                        "User-Agent",
                        "Coder Toolbox/$pluginVersion (${getOS()}; ${getArch()})",
                    ).build(),
                )
            }
            .addInterceptor {
                var request = it.request()
                val headers = getHeaders(url, settings.headerCommand)
                if (headers.isNotEmpty()) {
                    val reqBuilder = request.newBuilder()
                    headers.forEach { h -> reqBuilder.addHeader(h.key, h.value) }
                    request = reqBuilder.build()
                }
                it.proceed(request)
            }
            .addInterceptor(LoggingInterceptor(context))
            .build()
    }
}