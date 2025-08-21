package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon.IconType
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.actions.ActionDelimiter
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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

    override val isBusyCreatingNewEnvironment: MutableStateFlow<Boolean> = MutableStateFlow(false)

    companion object {
        fun emptyPage(ctx: CoderToolboxContext): UiPage = UiPage(ctx.i18n.pnotr(""))
    }
}

/**
 * An action that simply runs the provided callback.
 */
class Action(
    description: LocalizableString,
    closesPage: Boolean = false,
    enabled: () -> Boolean = { true },
    private val actionBlock: () -> Unit,
) : RunnableActionDescription {
    override val label: LocalizableString = description
    override val shouldClosePage: Boolean = closesPage
    override val isEnabled: Boolean = enabled()
    override fun run() {
        actionBlock()
    }
}

class CoderDelimiter(override val label: LocalizableString) : ActionDelimiter