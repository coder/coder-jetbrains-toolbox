package com.coder.toolbox.views.state


/**
 * A singleton that maintains the state of the coder setup wizard across Toolbox window lifecycle events.
 *
 * This is used to persist the wizard's progress (i.e., current step) between visibility changes
 * of the Toolbox window. Without this object, closing and reopening the window would reset the wizard
 * to its initial state by creating a new instance.
 */
object CoderCliSetupWizardState {
    private var currentStep = WizardStep.URL_REQUEST

    fun currentStep(): WizardStep = currentStep

    fun goToStep(step: WizardStep) {
        currentStep = step
    }

    fun goToNextStep() {
        currentStep = WizardStep.entries.toTypedArray()[(currentStep.ordinal + 1) % WizardStep.entries.size]
    }

    fun goToPreviousStep() {
        currentStep = WizardStep.entries.toTypedArray()[(currentStep.ordinal - 1) % WizardStep.entries.size]
    }

    fun resetSteps() {
        currentStep = WizardStep.URL_REQUEST
    }
}

enum class WizardStep {
    URL_REQUEST, TOKEN_REQUEST, CONNECT;
}