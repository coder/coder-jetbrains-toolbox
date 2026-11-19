package com.coder.toolbox.views.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URL

/**
 * Per-wizard mutable state: current step, form values, and OAuth session.
 *
 * Owned by a single [com.coder.toolbox.views.CoderSetupWizardPage] instance
 * and lives as long as that wizard does, so it survives Toolbox visibility
 * cycles without leaking across wizard recreations.
 */
class WizardModel {
    private val _step: MutableStateFlow<WizardStep> = MutableStateFlow(WizardStep.UrlRequest)
    val step: StateFlow<WizardStep> = _step

    var url: URL? = null
    var token: String? = null
    var oauthSession: CoderOAuthSessionContext? = null

    fun currentStep(): WizardStep = _step.value

    fun goTo(step: WizardStep) {
        _step.value = step
    }

    fun goToFirst() {
        _step.value = WizardStep.UrlRequest
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

sealed interface WizardStep {
    data object UrlRequest : WizardStep
    data object TokenRequest : WizardStep
    data class Connecting(val origin: ConnectionOrigin = ConnectionOrigin.FORM) : WizardStep
    data object SignedIn : WizardStep
}

enum class ConnectionOrigin {
    FORM, SUPPLIED_CREDENTIALS, SAVED_SESSION
}

sealed interface Credentials {
    object MTls : Credentials
    data class Token(val value: String) : Credentials
    data class OAuth(val session: CoderOAuthSessionContext) : Credentials
}

data class PendingOAuthConnection(
    val url: URL,
    val session: CoderOAuthSessionContext
)
