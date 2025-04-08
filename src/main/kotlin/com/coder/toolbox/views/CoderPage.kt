package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon
import com.jetbrains.toolbox.api.core.ui.icons.SvgIcon.IconType
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiPage

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
    private val context: CoderToolboxContext,
    title: LocalizableString,
    showIcon: Boolean = true,
) : UiPage(title) {

    /** Toolbox uses this to show notifications on the page. */
    private var notifier: ((Throwable) -> Unit)? = null

    /** Stores errors until the notifier is attached. */
    private var errorBuffer: MutableList<Throwable> = mutableListOf()

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

    /**
     * Show an error as a popup on this page.
     */
    fun notify(logPrefix: String, ex: Throwable) {
        context.logger.error(ex, logPrefix)
        // It is possible the error listener is not attached yet.
        notifier?.let { it(ex) } ?: errorBuffer.add(ex)
    }

    /**
     * Immediately notify any pending errors and store for later errors.
     */
    override fun setActionErrorNotifier(notifier: ((Throwable) -> Unit)?) {
        this.notifier = notifier
        notifier?.let {
            errorBuffer.forEach {
                notifier(it)
            }
            errorBuffer.clear()
        }
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
