package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.ex.APIResponseException
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon.IconType
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.actions.ActionDelimiter
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base page that handles the icon, displaying error notifications, and
 * getting field values.
 *
 * Note that it seems only the first page displays the icon, even if we
 * return an icon for every page.
 *
 * TODO: Any way to get the return key working for fields?  Right now you have
 *       to use the mouse.
 */
abstract class CoderPage(
    private val titleObservable: MutableStateFlow<LocalizableString>,
    showIcon: Boolean = true,
) : UiPage(titleObservable) {

    fun setTitle(title: LocalizableString) {
        titleObservable.update {
            title
        }
    }

    override val isBusyCreatingNewEnvironment: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Return the icon, if showing one.
     *
     * This seems to only work on the first page.
     */
    override val svgIcon: SvgIcon? = if (showIcon) {
        SvgIcon(
            this::class.java.getResourceAsStream("/icon.svg")?.readAllBytes() ?: byteArrayOf(),
            type = IconType.Masked
        )
    } else {
        SvgIcon(byteArrayOf(), type = IconType.Masked)
    }
}

/**
 * An action that simply runs the provided callback.
 */
class Action(
    private val context: CoderToolboxContext,
    private val description: String,
    closesPage: Boolean = false,
    highlightInRed: Boolean = false,
    enabled: () -> Boolean = { true },
    private val actionBlock: suspend () -> Unit,
) : RunnableActionDescription {
    override val label: LocalizableString = context.i18n.ptrl(description)
    override val shouldClosePage: Boolean = closesPage
    override val isEnabled: Boolean = enabled()
    override val isDangerous: Boolean = highlightInRed
    override fun run() {
        context.cs.launch(CoroutineName("$description Action")) {
            try {
                actionBlock()
            } catch (ex: Exception) {
                val textError = if (ex is APIResponseException) {
                    if (!ex.reason.isNullOrBlank()) {
                        ex.reason
                    } else ex.message
                } else ex.message
                context.logAndShowError("Error while running `$description`", textError ?: "", ex)
            }
        }
    }
}

class CoderDelimiter(override val label: LocalizableString) : ActionDelimiter