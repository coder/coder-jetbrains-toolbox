package com.coder.toolbox.views.state

import com.coder.toolbox.views.CoderPage
import com.coder.toolbox.views.CoderSetupWizardPage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The page that should currently be rendered in place of the environment list.
 */
sealed interface PageRoute {
    object None : PageRoute
    data class Wizard(val page: CoderSetupWizardPage) : PageRoute
}

/**
 * Holds the active [PageRoute]. The same page instance is returned across
 * Toolbox visibility cycles so in-flight work (e.g. an ongoing connect) is
 * preserved instead of being thrown away every time the window reopens.
 */
class PageRouter {
    private val route = MutableStateFlow<PageRoute>(PageRoute.None)

    val activeWizard: CoderSetupWizardPage?
        get() = (route.value as? PageRoute.Wizard)?.page

    /**
     * Returns the page already on this route, or builds a new one and
     * registers it.
     */
    fun getOrCreate(build: () -> CoderSetupWizardPage): CoderPage {
        (route.value as? PageRoute.Wizard)?.let { return it.page }

        return build().also { route.value = PageRoute.Wizard(it) }
    }

    /**
     * Replaces any active page with [page]. Used when an external trigger
     * (e.g. a deep link to a different deployment) needs to forcibly install
     * a new wizard.
     */
    fun navigate(page: CoderSetupWizardPage) {
        activeWizard?.dispose()
        route.value = PageRoute.Wizard(page)
    }

    fun clear() {
        activeWizard?.dispose()
        route.value = PageRoute.None
    }
}