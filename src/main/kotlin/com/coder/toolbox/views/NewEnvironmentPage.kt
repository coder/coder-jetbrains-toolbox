package com.coder.toolbox.views

import com.jetbrains.toolbox.api.ui.components.UiField


/**
 * A page for creating new environments.  It displays at the top of the
 * environments list.
 *
 * For now we just use this to display the deployment URL since we do not
 * support creating environments from the plugin.
 */
class NewEnvironmentPage(private val deploymentURL: String?) : CoderPage(deploymentURL ?: "") {
    override val fields: MutableList<UiField> = mutableListOf()
}
