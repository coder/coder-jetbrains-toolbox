package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.settings.WorkspaceScope
import com.coder.toolbox.settings.asWorkspaceFilterQuery
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.RadioButtonGroupField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val WORKSPACE_SEARCH_DEBOUNCE = 400.milliseconds

/**
 * A page for creating new environments.  It displays at the top of the
 * environments list.
 *
 * The expandable area provides controls that affect the workspace list.
 */
@OptIn(FlowPreview::class)
class NewEnvironmentPage(
    private val context: CoderToolboxContext,
    deploymentURL: LocalizableString,
    private val triggerWorkspaceRefresh: Channel<Boolean>,
) :
    CoderPage(MutableStateFlow(deploymentURL)) {
    private val workspaceSearchPlaceholder = context.i18n.pnotr(
        "owner:me name:workspace template:\"Docker Containers\" status:running"
    )
    private val initialWorkspaceScope = context.settingsStore.workspaceScope(context.deploymentUrl)
    private val workspaceSearchField = TextField(
        context.i18n.ptrl("Search workspaces"),
        "",
        TextType.General,
        weight = 1f,
        placeholder = workspaceSearchPlaceholder
    )
    private val allWorkspacesField = RadioButtonGroupField(
        listOf(context.i18n.ptrl("All")),
        workspaceScopeSelectionIndex(initialWorkspaceScope != WorkspaceScope.MY_WORKSPACES)
    )
    private val myWorkspacesField = RadioButtonGroupField(
        listOf(context.i18n.ptrl("My workspaces")),
        workspaceScopeSelectionIndex(initialWorkspaceScope == WorkspaceScope.MY_WORKSPACES)
    )
    private val workspaceScopeGroup = RowGroup(
        RowGroup.RowField(
            allWorkspacesField
        ),
        RowGroup.RowField(
            myWorkspacesField
        )
    )
    private var workspaceScopeJob: Job? = null
    private var workspaceSearchJob: Job? = null
    private val mutableWorkspaceSearchQuery = MutableStateFlow<String?>(null)

    val workspaceSearchQuery: StateFlow<String?> = mutableWorkspaceSearchQuery

    override val fields: StateFlow<List<UiField>> = MutableStateFlow(
        listOf(
            workspaceSearchField,
            workspaceScopeGroup
        )
    )

    override fun beforeShow() {
        updateWorkspaceScopeFields(context.settingsStore.workspaceScope(context.deploymentUrl))
        workspaceSearchJob?.cancel()
        workspaceSearchJob = context.cs.launch(CoroutineName("Workspace Search Header Setting")) {
            workspaceSearchField.contentState
                .drop(1)
                .debounce(WORKSPACE_SEARCH_DEBOUNCE)
                .map { it.asWorkspaceFilterQuery() }
                .distinctUntilChanged()
                .collect { query ->
                    if (query != mutableWorkspaceSearchQuery.value) {
                        mutableWorkspaceSearchQuery.update { query }
                        triggerWorkspaceRefresh.send(true)
                        context.logger.info("Workspace search changed, refreshing workspaces...")
                    }
                }
        }

        workspaceScopeJob?.cancel()
        workspaceScopeJob = context.cs.launch(CoroutineName("Workspace Scope Header Setting")) {
            launch {
                allWorkspacesField.selectedIndexState
                    .drop(1)
                    .filter { it == SELECTED_INDEX }
                    .collect {
                        updateWorkspaceScope(WorkspaceScope.ALL_WORKSPACES)
                    }
            }
            launch {
                myWorkspacesField.selectedIndexState
                    .drop(1)
                    .filter { it == SELECTED_INDEX }
                    .collect {
                        updateWorkspaceScope(WorkspaceScope.MY_WORKSPACES)
                    }
            }
        }
    }

    override fun afterHide() {
        workspaceSearchJob?.cancel()
        workspaceSearchJob = null
        workspaceScopeJob?.cancel()
        workspaceScopeJob = null
    }

    private suspend fun updateWorkspaceScope(scope: WorkspaceScope) {
        if (scope != context.settingsStore.workspaceScope(context.deploymentUrl)) {
            context.settingsStore.updateWorkspaceScope(context.deploymentUrl, scope)
            updateWorkspaceScopeFields(scope)
            triggerWorkspaceRefresh.send(true)
            context.logger.info(
                "Workspace list scope changed to $scope, refreshing workspaces..."
            )
        }
    }

    private fun updateWorkspaceScopeFields(scope: WorkspaceScope) {
        val isMyWorkspaces = scope == WorkspaceScope.MY_WORKSPACES
        allWorkspacesField.selectedIndexState.update { workspaceScopeSelectionIndex(!isMyWorkspaces) }
        myWorkspacesField.selectedIndexState.update { workspaceScopeSelectionIndex(isMyWorkspaces) }
    }

    private fun workspaceScopeSelectionIndex(isSelected: Boolean): Int =
        if (isSelected) SELECTED_INDEX else UNSELECTED_INDEX

    private companion object {
        const val SELECTED_INDEX = 0
        const val UNSELECTED_INDEX = -1
    }
}
