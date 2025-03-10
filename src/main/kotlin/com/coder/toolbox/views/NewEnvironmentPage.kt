package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


/**
 * A page for creating new environments.  It displays at the top of the
 * environments list.
 *
 * For now we just use this to display the deployment URL since we do not
 * support creating environments from the plugin.
 */
class NewEnvironmentPage(context: CoderToolboxContext, deploymentURL: LocalizableString) :
    CoderPage(context, deploymentURL) {
    override val fields: StateFlow<List<UiField>> = MutableStateFlow(emptyList())
}
