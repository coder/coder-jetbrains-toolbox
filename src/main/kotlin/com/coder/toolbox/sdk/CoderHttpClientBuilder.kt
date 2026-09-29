package com.coder.toolbox.sdk

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.interceptors.Interceptors
import com.coder.toolbox.sdk.proxy.applyProxySettings
import com.coder.toolbox.util.CoderHostnameVerifier
import com.coder.toolbox.util.ReloadableTlsContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object CoderHttpClientBuilder {
    fun build(
        context: CoderToolboxContext,
        interceptors: List<Interceptor>,
        tlsContext: ReloadableTlsContext
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .applyProxySettings(context)
            .sslSocketFactory(tlsContext.sslSocketFactory, tlsContext.trustManager)
            .hostnameVerifier(CoderHostnameVerifier(context.settingsStore.tls.altHostname))
            .retryOnConnectionFailure(true)
            // Detect silent connection loss even when a workspace build produces no log messages.
            .pingInterval(30, TimeUnit.SECONDS)

        interceptors.forEach { interceptor ->
            builder.addInterceptor(interceptor)
        }
        return builder.build()
    }

    fun default(context: CoderToolboxContext): OkHttpClient {
        val interceptors = buildList {
            add((Interceptors.userAgent(PluginManager.pluginInfo.version)))
            add(Interceptors.logging(context))
        }
        return build(
            context,
            interceptors,
            ReloadableTlsContext(context.settingsStore.readOnly().tls)
        )
    }
}
