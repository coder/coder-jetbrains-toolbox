package com.coder.toolbox

import com.coder.toolbox.store.CoderSecretsStore
import com.coder.toolbox.store.CoderSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.connection.ClientHelper
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.ui.EnvironmentUiPageManager
import com.jetbrains.toolbox.api.ui.ToolboxUi
import kotlinx.coroutines.CoroutineScope

data class CoderToolboxContext(
    val ui: ToolboxUi,
    val envPageManager: EnvironmentUiPageManager,
    val envStateColorPalette: EnvironmentStateColorPalette,
    val ideOrchestrator: ClientHelper,
    val cs: CoroutineScope,
    val logger: Logger,
    val i18n: LocalizableStringFactory,
    val settingsStore: CoderSettingsStore,
    val secrets: CoderSecretsStore
)
