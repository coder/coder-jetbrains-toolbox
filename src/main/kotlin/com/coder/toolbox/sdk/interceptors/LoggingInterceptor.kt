package com.coder.toolbox.sdk.interceptors

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.HttpLoggingVerbosity
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.nio.charset.StandardCharsets

private val SENSITIVE_HEADERS = setOf("Coder-Session-Token", "Proxy-Authorization")

class LoggingInterceptor(private val context: CoderToolboxContext) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val logLevel = context.settingsStore.httpClientLogLevel
        if (logLevel == HttpLoggingVerbosity.NONE) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        logRequest(request, logLevel)

        val response = chain.proceed(request)
        logResponse(response, request, logLevel)

        return response
    }

    private fun logRequest(request: Request, logLevel: HttpLoggingVerbosity) {
        val log = buildString {
            append("request --> ${request.method} ${request.url}")

            if (logLevel >= HttpLoggingVerbosity.HEADERS) {
                append("\n${request.headers.sanitized()}")
            }

            if (logLevel == HttpLoggingVerbosity.BODY) {
                request.body?.let { body ->
                    append("\n${body.toPrintableString()}")
                }
            }
        }

        context.logger.info(log)
    }

    private fun logResponse(response: Response, request: Request, logLevel: HttpLoggingVerbosity) {
        val log = buildString {
            append("response <-- ${response.code} ${response.message} ${request.url}")

            if (logLevel >= HttpLoggingVerbosity.HEADERS) {
                append("\n${response.headers.sanitized()}")
            }

            if (logLevel == HttpLoggingVerbosity.BODY) {
                response.body?.let { body ->
                    append("\n${body.toPrintableString()}")
                }
            }
        }

        context.logger.info(log)
    }
}

// Extension functions for cleaner code
private fun Headers.sanitized(): String = buildString {
    this@sanitized.forEach { (name, value) ->
        val displayValue = if (name in SENSITIVE_HEADERS) "<redacted>" else value
        append("$name: $displayValue\n")
    }
}

private fun RequestBody.toPrintableString(): String {
    if (!contentType().isPrintable()) {
        return "[Binary body: ${contentLength().formatBytes()}, ${contentType()}]"
    }

    return try {
        val buffer = Buffer()
        writeTo(buffer)
        buffer.readString(contentType()?.charset() ?: StandardCharsets.UTF_8)
    } catch (e: Exception) {
        "[Error reading body: ${e.message}]"
    }
}

private fun ResponseBody.toPrintableString(): String {
    if (!contentType().isPrintable()) {
        return "[Binary body: ${contentLength().formatBytes()}, ${contentType()}]"
    }

    return try {
        val source = source()
        source.request(Long.MAX_VALUE)
        source.buffer.clone().readString(contentType()?.charset() ?: StandardCharsets.UTF_8)
    } catch (e: Exception) {
        "[Error reading body: ${e.message}]"
    }
}

private fun MediaType?.isPrintable(): Boolean = when {
    this == null -> false
    type == "text" -> true
    subtype == "json" || subtype.endsWith("+json") -> true
    else -> false
}

private fun Long.formatBytes(): String = when {
    this < 0 -> "unknown"
    this < 1024 -> "${this}B"
    this < 1024 * 1024 -> "${this / 1024}KB"
    this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)}MB"
    else -> "${this / (1024 * 1024 * 1024)}GB"
}