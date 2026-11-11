package com.coder.toolbox.views.state

import com.coder.toolbox.views.CoderSetupWizardPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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

    val activePage: CoderSetupWizardPage?
        get() = (route.value as? PageRoute.Wizard)?.page

    val hasActiveWizard: Boolean
        get() = activePage != null

    val pendingOAuthConnection: PendingOAuthConnection?
        get() = activePage?.pendingOAuthConnection()

    /**
     * Returns the page already on this route, or builds a new one and
     * registers it.
     */
    @Synchronized
    fun getOrCreate(build: () -> CoderSetupWizardPage): CoderSetupWizardPage {
        activePage?.let { return it }

        return build().also { route.value = PageRoute.Wizard(it) }
    }

    /**
     * Replaces any active page with [page]. Used when an external trigger
     * (e.g. a deep link to a different deployment) needs to forcibly install
     * a new wizard.
     */
    @Synchronized
    fun navigate(page: CoderSetupWizardPage) {
        activePage?.dispose()
        route.value = PageRoute.Wizard(page)
    }

    @Synchronized
    fun clear() {
        activePage?.dispose()
        route.value = PageRoute.None
    }

    @Synchronized
    fun finishSignIn(connection: Job, startSession: () -> Unit) {
        // Replacing a wizard cancels its connection. Only the winner can publish a session.
        connection.ensureActive()
        startSession()
        val wizard = activePage ?: return
        route.value = PageRoute.None
        wizard.model.goTo(WizardStep.SIGNED_IN)
    }
}
