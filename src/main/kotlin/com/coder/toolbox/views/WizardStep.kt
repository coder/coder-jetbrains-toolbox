package com.coder.toolbox.views

import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.RowGroup

interface WizardStep {
    val panel: RowGroup
    val nextButtonTitle: LocalizableString?
    val closesWizard: Boolean

    /**
     * Callback when step is visible
     */
    fun onVisible()

    /**
     * Callback when user hits next.
     * Returns true if it moved the wizard one step forward.
     */
    fun onNext(): Boolean
    fun onBack()
}