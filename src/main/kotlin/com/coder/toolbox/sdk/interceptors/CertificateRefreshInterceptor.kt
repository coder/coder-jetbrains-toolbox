package com.coder.toolbox.sdk.interceptors

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.util.ReloadableTlsContext
import okhttp3.Interceptor
import okhttp3.Response
import org.zeroturnaround.exec.ProcessExecutor
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class CertificateRefreshInterceptor(
    private val context: CoderToolboxContext,
    private val tlsContext: ReloadableTlsContext
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (e: Exception) {
            if ((e is SSLHandshakeException || e is SSLPeerUnverifiedException) && (e.message?.contains("certificate_expired") == true)) {
                val command = context.settingsStore.tls.certRefreshCommand
                if (command.isNullOrBlank()) {
                    throw IllegalStateException(
                        "Certificate expiration interceptor was set but the refresh command was removed in the meantime",
                        e
                    )
                }

                context.logger.info("SSL handshake exception encountered: certificates expired. Running certificate refresh command: $command")
                try {
                    val result = ProcessExecutor()
                        .command(command.split(" ").toList())
                        .exitValueNormal()
                        .readOutput(true)
                        .execute()
                    context.logger.info("`$command`: ${result.outputUTF8()}")

                    if (result.exitValue == 0) {
                        context.logger.info("Certificate refresh command executed successfully. Reloading SSL certificates.")
                        tlsContext.reload()
                        // Retry the request
                        return chain.proceed(request)
                    } else {
                        context.logger.error("Certificate refresh command failed with exit code ${result.exitValue}")
                    }
                } catch (ex: Exception) {
                    context.logger.error(ex, "Failed to execute certificate refresh command")
                }
            }
            throw e
        }
    }
}
