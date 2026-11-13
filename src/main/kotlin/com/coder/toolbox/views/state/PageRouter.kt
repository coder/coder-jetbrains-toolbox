package com.coder.toolbox.views.state

import com.coder.toolbox.views.CoderSetupWizardPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/**
 * Holds the active wizard. The same page instance is returned across
 * Toolbox visibility cycles so in-flight work (e.g. an ongoing connect) is
 * preserved instead of being thrown away every time the window reopens.
 */
class PageRouter {
    @Volatile
    var activePage: CoderSetupWizardPage? = null
        private set

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

        return build().also(::navigate)
    }

    /**
     * Replaces any active page with [page]. Used when an external trigger
     * (e.g. a deep link to a different deployment) needs to forcibly install
     * a new wizard.
     */
    @Synchronized
    fun navigate(page: CoderSetupWizardPage) {
        activePage?.dispose()
        activePage = page
        // A restore can finish immediately. Publish its wizard before starting the job.
        page.startSavedSessionRestore()
    }

    @Synchronized
    fun clear() {
        activePage?.dispose()
        activePage = null
    }

    @Synchronized
    fun finishSignIn(connection: Job, startSession: () -> Unit) {
        // Replacing a wizard cancels its connection. Only the winner can publish a session.
        connection.ensureActive()
        startSession()
        val wizard = activePage ?: return
        activePage = null
        wizard.model.goTo(WizardStep.SignedIn)
    }
}
