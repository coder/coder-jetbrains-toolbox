package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.LinkField
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update


/**
 * A page for creating new environments.  It displays at the top of the
 * environments list.
 *
 * For now we just use this to display the deployment URL since we do not
 * support creating environments from the plugin.
 */
class NewEnvironmentPage(private val context: CoderToolboxContext, title: LocalizableString, initialUrl: String) :
    CoderPage(context, title) {
    override val fields: MutableStateFlow<List<UiField>> =
        MutableStateFlow(listOf(LinkField(context.i18n.pnotr(initialUrl), initialUrl)))

    fun refreshUrl(url: String) {
        fields.update {
            listOf(LinkField(context.i18n.pnotr(url), url))
        }
    }
}
