package com.coder.toolbox.logger

import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import com.jetbrains.toolbox.api.core.diagnostics.Logger as ToolboxLogger

object CoderLoggerFactory : ILoggerFactory {
    var tLogger: ToolboxLogger? = null

    fun getLogger(clazz: Class<Any>): Logger = getLogger(clazz.name)
    override fun getLogger(clazzName: String): Logger = LoggerImpl(clazzName, tLogger)
}