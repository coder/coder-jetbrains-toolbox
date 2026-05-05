package com.coder.toolbox.views.state

import com.coder.toolbox.views.CoderCliSetupWizardPage
import com.jetbrains.toolbox.api.ui.components.UiPage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The page that should currently be rendered in place of the environment list.
 */
sealed interface OverrideRoute {
    object None : OverrideRoute
    data class Wizard(val page: CoderCliSetupWizardPage) : OverrideRoute
}

/**
 * Holds the active [OverrideRoute]. The same page instance is returned across
 * Toolbox visibility cycles so in-flight work (e.g. an ongoing connect) is
 * preserved instead of being thrown away every time the window reopens.
 */
class PageRouter {
    private val route = MutableStateFlow<OverrideRoute>(OverrideRoute.None)

    val activePage: UiPage?
        get() = (route.value as? OverrideRoute.Wizard)?.page

    val activeWizard: CoderCliSetupWizardPage?
        get() = (route.value as? OverrideRoute.Wizard)?.page

    /**
     * Returns the wizard already on this route, or builds a new one and
     * registers it.
     */
    fun requireWizard(build: () -> CoderCliSetupWizardPage): CoderCliSetupWizardPage {
        (route.value as? OverrideRoute.Wizard)?.let { return it.page }
        val page = build()
        route.value = OverrideRoute.Wizard(page)
        return page
    }

    /**
     * Replaces any active page with [page]. Used when an external trigger
     * (e.g. a deep link to a different deployment) needs to forcibly install
     * a new wizard.
     */
    fun replaceWith(page: CoderCliSetupWizardPage) {
        route.value = OverrideRoute.Wizard(page)
    }

    fun clear() {
        route.value = OverrideRoute.None
    }
}