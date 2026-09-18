package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.diagnostics.CoderProviderLogCollector
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.components.LabelField
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class CoderDiagnosticCollectorPage(
    private val context: CoderToolboxContext,
    private val collector: CoderProviderLogCollector,
) : CoderPage(MutableStateFlow(context.i18n.ptrl("Collect detailed deployment and Toolbox logs"))) {
    override val description = context.i18n.ptrl(
        "Collect diagnostics for all accessible workspaces and agents, plus Toolbox and JetBrains daemon logs."
    )
    private val status = LabelField(context.i18n.ptrl("Preparing log collection…"))
    private val detail = LabelField(context.i18n.pnotr(""))
    private var collectorJob: Job? = null
    private var cancelled = false
    override val fields: MutableStateFlow<List<UiField>> = MutableStateFlow(listOf(status, detail))
    override val actionButtons: MutableStateFlow<List<ActionDescription>> = MutableStateFlow(emptyList())
    override val isCancellable = MutableStateFlow(true)
    val isFinished: Boolean get() = cancelled || collectorJob?.isCompleted == true

    @Suppress("TooGenericExceptionCaught")
    override fun beforeShow() {
        if (collectorJob != null || cancelled) return
        isBusy.value = true
        collectorJob = context.cs.launch {
            try {
                val archive = collector.collect {
                    status.textState.value = context.i18n.ptrl(it.message)
                    detail.textState.value = context.i18n.pnotr(it.detail)
                }
                status.textState.value = context.i18n.ptrl(
                    "Log archive created. Any collection failures are reported inside the archive."
                )
                actionButtons.value = listOf(Action(context, "Show log archive") { context.desktop.openPath(archive) })
                context.desktop.openPath(archive)
            } catch (ex: CancellationException) {
                status.textState.value = context.i18n.ptrl("Log collection cancelled.")
                throw ex
            } catch (ex: Exception) {
                context.logger.warn(ex, "Could not collect Coder provider logs")
                status.textState.value = context.i18n.ptrl(
                    "Could not create the log archive. Check the Toolbox logs for details."
                )
            } finally {
                detail.textState.value = context.i18n.pnotr("")
                isBusy.value = false
                isCancellable.value = false
            }
        }
    }

    override fun cancel() {
        cancelled = true
        collectorJob?.cancel()
    }
}
