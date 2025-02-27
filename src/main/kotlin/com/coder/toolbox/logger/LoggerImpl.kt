package com.coder.toolbox.logger

import org.slf4j.Logger
import org.slf4j.Marker
import com.jetbrains.toolbox.api.core.diagnostics.Logger as ToolboxLogger

class LoggerImpl(private val clazzName: String, private val tLogger: ToolboxLogger?) : Logger {
    override fun getName(): String = clazzName

    override fun isTraceEnabled(): Boolean = true

    override fun trace(message: String) {
        tLogger?.trace(message)
    }

    override fun trace(message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.trace(it, message) } ?: tLogger?.trace(message)
    }

    override fun trace(message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.trace(it, message) } ?: tLogger?.trace(message)
    }

    override fun trace(message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.trace(it, message) } ?: tLogger?.trace(message)
    }

    override fun trace(message: String, exception: Throwable) {
        tLogger?.trace(exception, message)
    }

    override fun isTraceEnabled(marker: Marker): Boolean = true

    override fun trace(marker: Marker, message: String) {
        tLogger?.trace(message)
    }

    override fun trace(marker: Marker, message: String, arg: Any) {
        extractThrowable(arg)?.let { tLogger?.trace(it, message) } ?: tLogger?.trace(message)
    }

    override fun trace(marker: Marker, message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.trace(it, message) } ?: tLogger?.trace(message)
    }

    override fun trace(marker: Marker, message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.trace(it, message) } ?: tLogger?.trace(message)
    }

    override fun trace(marker: Marker, message: String, exception: Throwable) {
        tLogger?.trace(exception, message)
    }

    override fun isDebugEnabled(): Boolean = true

    override fun debug(message: String) {
        tLogger?.debug(message)
    }

    override fun debug(message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.debug(it, message) } ?: tLogger?.debug(message)
    }

    override fun debug(message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.debug(it, message) } ?: tLogger?.debug(message)
    }

    override fun debug(message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.debug(it, message) } ?: tLogger?.debug(message)
    }

    override fun debug(message: String, exception: Throwable) {
        tLogger?.debug(exception, message)
    }

    override fun isDebugEnabled(marker: Marker): Boolean = true

    override fun debug(marker: Marker, message: String) {
        tLogger?.debug(message)
    }

    override fun debug(marker: Marker, message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.debug(it, message) } ?: tLogger?.debug(message)
    }

    override fun debug(marker: Marker, message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.debug(it, message) } ?: tLogger?.debug(message)
    }

    override fun debug(marker: Marker, message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.debug(it, message) } ?: tLogger?.debug(message)
    }

    override fun debug(marker: Marker, message: String, exception: Throwable) {
        tLogger?.debug(exception, message)
    }

    override fun isInfoEnabled(): Boolean = true

    override fun info(message: String) {
        tLogger?.info(message)
    }

    override fun info(message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.info(it, message) } ?: tLogger?.info(message)
    }

    override fun info(message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.info(it, message) } ?: tLogger?.info(message)
    }

    override fun info(message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.info(it, message) } ?: tLogger?.info(message)
    }

    override fun info(message: String, exception: Throwable) {
        tLogger?.info(exception, message)
    }

    override fun isInfoEnabled(marker: Marker): Boolean = true

    override fun info(marker: Marker, message: String) {
        tLogger?.info(message)
    }

    override fun info(marker: Marker, message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.info(it, message) } ?: tLogger?.info(message)
    }

    override fun info(marker: Marker, message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.info(it, message) } ?: tLogger?.info(message)
    }

    override fun info(marker: Marker, message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.info(it, message) } ?: tLogger?.info(message)
    }

    override fun info(marker: Marker, message: String, exception: Throwable) {
        tLogger?.info(exception, message)
    }

    override fun isWarnEnabled(): Boolean = true

    override fun warn(message: String) {
        tLogger?.warn(message)
    }

    override fun warn(message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.warn(it, message) } ?: tLogger?.warn(message)
    }

    override fun warn(message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.warn(it, message) } ?: tLogger?.warn(message)
    }

    override fun warn(message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.warn(it, message) } ?: tLogger?.warn(message)
    }

    override fun warn(message: String, exception: Throwable) {
        tLogger?.warn(exception, message)
    }

    override fun isWarnEnabled(marker: Marker): Boolean = true

    override fun warn(marker: Marker, message: String) {
        tLogger?.warn(message)
    }

    override fun warn(marker: Marker, message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.warn(it, message) } ?: tLogger?.warn(message)
    }

    override fun warn(marker: Marker, message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.warn(it, message) } ?: tLogger?.warn(message)
    }

    override fun warn(marker: Marker, message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.warn(it, message) } ?: tLogger?.warn(message)
    }

    override fun warn(marker: Marker, message: String, exception: Throwable) {
        tLogger?.warn(exception, message)
    }

    override fun isErrorEnabled(): Boolean = true

    override fun error(message: String) {
        tLogger?.error(message)
    }

    override fun error(message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.error(it, message) } ?: tLogger?.error(message)
    }

    override fun error(message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.error(it, message) } ?: tLogger?.error(message)
    }

    override fun error(message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.error(it, message) } ?: tLogger?.error(message)
    }

    override fun error(message: String, exception: Throwable) {
        tLogger?.error(exception, message)
    }

    override fun isErrorEnabled(marker: Marker): Boolean = true

    override fun error(marker: Marker, message: String) {
        tLogger?.error(message)
    }

    override fun error(marker: Marker, message: String, arg: Any?) {
        extractThrowable(arg)?.let { tLogger?.error(it, message) } ?: tLogger?.error(message)
    }

    override fun error(marker: Marker, message: String, firstArg: Any?, secondArg: Any?) {
        extractThrowable(firstArg, secondArg)?.let { tLogger?.error(it, message) } ?: tLogger?.error(message)
    }

    override fun error(marker: Marker, message: String, vararg args: Any?) {
        extractThrowable(args)?.let { tLogger?.error(it, message) } ?: tLogger?.error(message)
    }

    override fun error(marker: Marker, message: String, exception: Throwable) {
        tLogger?.error(exception, message)
    }

    companion object {
        fun extractThrowable(vararg args: Any?): Throwable? = args.firstOrNull { it is Throwable } as? Throwable

        fun extractThrowable(arg: Any?): Throwable? = arg as? Throwable
    }
}