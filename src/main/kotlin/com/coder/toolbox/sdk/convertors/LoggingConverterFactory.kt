package com.coder.toolbox.sdk.convertors

import com.coder.toolbox.CoderToolboxContext
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

class LoggingConverterFactory private constructor(
    private val context: CoderToolboxContext,
    private val delegate: Converter.Factory,
) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        // Get the delegate converter
        val delegateConverter = delegate.responseBodyConverter(type, annotations, retrofit)
            ?: return null

        @Suppress("UNCHECKED_CAST")
        return LoggingMoshiConverter(context, delegateConverter as Converter<ResponseBody, Any?>)
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? {
        return delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
    }

    override fun stringConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<*, String>? {
        return delegate.stringConverter(type, annotations, retrofit)
    }

    companion object {
        fun wrap(
            context: CoderToolboxContext,
            delegate: Converter.Factory,
        ): LoggingConverterFactory {
            return LoggingConverterFactory(context, delegate)
        }
    }
}