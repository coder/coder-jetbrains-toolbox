package com.coder.toolbox.views.state


object AuthWizardState {
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
    URL_REQUEST, TOKEN_REQUEST, LOGIN;
}