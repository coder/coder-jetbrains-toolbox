package com.coder.toolbox.sdk.convertors

import com.coder.toolbox.CoderToolboxContext
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Converter

class LoggingMoshiConverter(
    private val context: CoderToolboxContext,
    private val delegate: Converter<ResponseBody, Any?>
) : Converter<ResponseBody, Any> {

    override fun convert(value: ResponseBody): Any? {
        val bodyString = value.string()

        return try {
            // Parse with Moshi
            delegate.convert(bodyString.toResponseBody(value.contentType()))
        } catch (e: Exception) {
            // Log the raw content that failed to parse
            context.logger.error(
                """
                |Moshi parsing failed:
                |Content-Type: ${value.contentType()}
                |Content: $bodyString
                |Error: ${e.message}
            """.trimMargin()
            )

            // Re-throw so the onFailure callback still gets called
            throw e
        }
    }
}