package com.coder.toolbox.sdk.interceptors

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.HttpLoggingVerbosity
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.nio.charset.StandardCharsets

class LoggingInterceptor(private val context: CoderToolboxContext) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val logLevel = context.settingsStore.httpClientLogLevel
        if (logLevel == HttpLoggingVerbosity.NONE) {
            return chain.proceed(chain.request())
        }
        val request = chain.request()
        val requestLog = StringBuilder()
        requestLog.append("request --> ${request.method} ${request.url}\n")
        if (logLevel == HttpLoggingVerbosity.HEADERS) {
            requestLog.append(request.headers.toSanitizedString())
        }
        if (logLevel == HttpLoggingVerbosity.BODY) {
            request.body.toPrintableString()?.let {
                requestLog.append(it)
            }
        }
        context.logger.info(requestLog.toString())

        val response = chain.proceed(request)
        val responseLog = StringBuilder()
        responseLog.append("response <-- ${response.code} ${response.message} ${request.url}\n")
        if (logLevel == HttpLoggingVerbosity.HEADERS) {
            responseLog.append(response.headers.toSanitizedString())
        }
        if (logLevel == HttpLoggingVerbosity.BODY) {
            response.body.toPrintableString()?.let {
                responseLog.append(it)
            }
        }

        context.logger.info(responseLog.toString())
        return response
    }

    private fun Headers.toSanitizedString(): String {
        val result = StringBuilder()
        this.forEach {
            if (it.first == "Coder-Session-Token" || it.first == "Proxy-Authorization") {
                result.append("${it.first}: <redacted>\n")
            } else {
                result.append("${it.first}: ${it.second}\n")
            }
        }
        return result.toString()
    }

    /**
     * Converts a RequestBody to a printable string representation.
     * Handles different content types appropriately.
     *
     * @return String representation of the body, or metadata if not readable
     */
    fun RequestBody?.toPrintableString(): String? {
        if (this == null) {
            return null
        }

        if (!contentType().isPrintable()) {
            return "[Binary request body: ${contentLength().formatBytes()}, Content-Type: ${contentType()}]\n"
        }

        return try {
            val buffer = Buffer()
            writeTo(buffer)

            val charset = contentType()?.charset() ?: StandardCharsets.UTF_8
            buffer.readString(charset)
        } catch (e: Exception) {
            "[Error reading request body: ${e.message}]\n"
        }
    }

    /**
     * Converts a ResponseBody to a printable string representation.
     * Handles different content types appropriately.
     *
     * @return String representation of the body, or metadata if not readable
     */
    fun ResponseBody?.toPrintableString(): String? {
        if (this == null) {
            return null
        }

        if (!contentType().isPrintable()) {
            return "[Binary response body: ${contentLength().formatBytes()}, Content-Type: ${contentType()}]\n"
        }

        return try {
            val source = source()
            source.request(Long.MAX_VALUE)
            val charset = contentType()?.charset() ?: StandardCharsets.UTF_8
            source.buffer.clone().readString(charset)
        } catch (e: Exception) {
            "[Error reading response body: ${e.message}]\n"
        }
    }

    /**
     * Checks if a MediaType represents printable/readable content
     */
    private fun MediaType?.isPrintable(): Boolean {
        if (this == null) return false

        return when {
            // Text types
            type == "text" -> true

            // JSON variants
            subtype == "json" -> true
            subtype.endsWith("+json") -> true

            // Default to non-printable for safety
            else -> false
        }
    }

    /**
     * Formats byte count in human-readable format
     */
    private fun Long.formatBytes(): String {
        return when {
            this < 0 -> "unknown size"
            this < 1024 -> "${this}B"
            this < 1024 * 1024 -> "${this / 1024}KB"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)}MB"
            else -> "${this / (1024 * 1024 * 1024)}GB"
        }
    }
}