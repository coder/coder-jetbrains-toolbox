package com.coder.toolbox.views.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A singleton that maintains the state of the coder setup wizard across Toolbox window lifecycle events.
 *
 * This is used to persist the wizard's progress (i.e., current step) between visibility changes
 * of the Toolbox window. Without this object, closing and reopening the window would reset the wizard
 * to its initial state by creating a new instance.
 */
object CoderSetupWizardState {
    private val currentStep = MutableStateFlow(WizardStep.URL_REQUEST)
    val step: StateFlow<WizardStep> = currentStep

    fun currentStep(): WizardStep = currentStep.value

    fun goToStep(step: WizardStep) {
        currentStep.value = step
    }

    fun goToNextStep() {
        currentStep.value = WizardStep.entries.toTypedArray()[(currentStep.value.ordinal + 1) % WizardStep.entries.size]
    }

    fun goToPreviousStep() {
        val entries = WizardStep.entries.toTypedArray()
        currentStep.value = entries[(currentStep.value.ordinal - 1 + entries.size) % entries.size]
    }

    fun goToLastStep() {
        currentStep.value = WizardStep.CONNECT
    }

    fun goToFirstStep() {
        currentStep.value = WizardStep.URL_REQUEST
    }

    fun goToDone() {
        currentStep.value = WizardStep.DONE
    }
}

enum class WizardStep {
    URL_REQUEST, TOKEN_REQUEST, CONNECT, DONE;
}
