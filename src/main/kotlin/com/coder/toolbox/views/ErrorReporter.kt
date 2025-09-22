package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.ex.APIResponseException
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ErrorReporter {

    /**
     * Logs and show errors as popups.
     */
    abstract fun report(message: String, ex: Throwable)

    /**
     * Processes any buffered errors when the application becomes visible.
     */
    abstract fun flush()

    companion object {
        fun create(
            context: CoderToolboxContext,
            visibilityState: StateFlow<ProviderVisibilityState>,
            callerClass: Class<*>
        ): ErrorReporter = ErrorReporterImpl(context, visibilityState, callerClass)
    }
}

private class ErrorReporterImpl(
    private val context: CoderToolboxContext,
    private val visibilityState: StateFlow<ProviderVisibilityState>,
    private val callerClass: Class<*>
) : ErrorReporter() {
    private val errorBuffer = mutableListOf<Throwable>()

    override fun report(message: String, ex: Throwable) {
        context.logger.error(ex, "[${callerClass.simpleName}] $message")
        if (!visibilityState.value.applicationVisible) {
            context.logger.debug("Toolbox is not yet visible, scheduling error to be displayed later")
            errorBuffer.add(ex)
            return
        }
        showError(ex)
    }

    private fun showError(ex: Throwable) {
        val textError = if (ex is APIResponseException) {
            if (!ex.reason.isNullOrBlank()) {
                ex.reason
            } else ex.message
        } else ex.message ?: ex.toString()
        context.cs.launch {
            context.ui.showSnackbar(
                UUID.randomUUID().toString(),
                context.i18n.ptrl("Error encountered while setting up Coder"),
                context.i18n.pnotr(textError ?: ""),
                context.i18n.ptrl("Dismiss")
            )
        }
    }


    override fun flush() {
        if (errorBuffer.isNotEmpty() && visibilityState.value.applicationVisible) {
            errorBuffer.forEach {
                showError(it)
            }
            errorBuffer.clear()
        }
    }
}