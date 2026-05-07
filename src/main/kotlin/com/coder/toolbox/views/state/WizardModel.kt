package com.coder.toolbox.views.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URL

/**
 * Per-wizard mutable state: current step, form values, and OAuth session.
 *
 * Owned by a single [com.coder.toolbox.views.CoderCliSetupWizardPage] instance
 * and lives as long as that wizard does, so it survives Toolbox visibility
 * cycles without leaking across wizard recreations.
 */
class WizardModel {
    private val _step: MutableStateFlow<WizardStep> = MutableStateFlow(WizardStep.URL_REQUEST)
    val step: StateFlow<WizardStep> = _step

    var url: URL? = null
    var token: String? = null
    var oauthSession: CoderOAuthSessionContext? = null

    fun currentStep(): WizardStep = _step.value

    fun goTo(step: WizardStep) {
        _step.value = step
    }

    fun goToNext() {
        val entries = WizardStep.entries
        _step.value = entries[(_step.value.ordinal + 1) % entries.size]
    }

    fun goToPrevious() {
        val entries = WizardStep.entries
        _step.value = entries[(_step.value.ordinal - 1 + entries.size) % entries.size]
    }

    fun goToFirst() {
        _step.value = WizardStep.URL_REQUEST
    }

    fun goToLast() {
        _step.value = WizardStep.CONNECT
    }

    fun hasUrl(): Boolean = url != null
    fun hasToken(): Boolean = !token.isNullOrBlank()
    fun hasOAuthSession(): Boolean = oauthSession?.tokenResponse?.accessToken != null

    fun isNotReadyForAuth(): Boolean = !(hasUrl() && (hasToken() || hasOAuthSession()))

    fun clearFormData() {
        url = null
        token = null
        oauthSession = null
    }
}

enum class WizardStep {
    URL_REQUEST, TOKEN_REQUEST, CONNECT;
}