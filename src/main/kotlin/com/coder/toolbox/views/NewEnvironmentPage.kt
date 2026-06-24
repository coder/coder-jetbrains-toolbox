package com.coder.toolbox.views

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.v2.models.Template
import com.coder.toolbox.util.CUSTOM_WORKSPACE_FILTER_NAME
import com.coder.toolbox.util.DEFAULT_WORKSPACE_FILTER_QUERY
import com.coder.toolbox.util.WORKSPACE_FILTER_PRESETS
import com.coder.toolbox.util.parseFilterQuery
import com.coder.toolbox.util.presetNameForQuery
import com.coder.toolbox.util.withFilterTerm
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.ui.components.ComboBoxField
import com.jetbrains.toolbox.api.ui.components.RowGroup
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val WORKSPACE_SEARCH_DEBOUNCE = 400.milliseconds

private const val TEMPLATE_KEY = "template"
private const val STATUS_KEY = "status"

/**
 * A page for creating new environments. It displays at the top of the environments list.
 *
 * The expandable area mirrors the Coder web dashboard's workspace filtering: a free-form search
 * field whose text is sent to the server as-is, plus Filters/Template/Status dropdowns that edit
 * individual terms of that same query.
 * */
@Suppress("DEPRECATION")
@OptIn(FlowPreview::class)
class NewEnvironmentPage(
    private val context: CoderToolboxContext,
    deploymentURL: LocalizableString,
    private val workspaceRefreshTrigger: Channel<Boolean>,
    private val templatesProvider: suspend () -> List<Template>,
) : CoderPage(MutableStateFlow(deploymentURL)) {
    private val workspaceSearchField = TextField(
        context.i18n.ptrl("Search workspaces"),
        DEFAULT_WORKSPACE_FILTER_QUERY,
        TextType.General,
        weight = 1f,
        placeholder = context.i18n.pnotr("")
    )

    // Shows the server's validation message under the search bar; blank when there is no error.
    private val errorField = ValidationErrorField(context.i18n.pnotr(""))

    // Dropdown selections. An empty string means "no filter for this key" (the leading "All ..." option).
    private val filtersSelection = MutableStateFlow(DEFAULT_WORKSPACE_FILTER_QUERY.presetNameForQuery())
    private val templateSelection = MutableStateFlow("")
    private val statusSelection = MutableStateFlow("")

    private val filterPresetOptions: List<ComboBoxField.LabelledValue<String>> =
        WORKSPACE_FILTER_PRESETS.map { ComboBoxField.LabelledValue(context.i18n.pnotr(it.name), it.name) } +
                ComboBoxField.LabelledValue(
                    context.i18n.pnotr(CUSTOM_WORKSPACE_FILTER_NAME),
                    CUSTOM_WORKSPACE_FILTER_NAME
                )

    private val statusOptions: List<ComboBoxField.LabelledValue<String>> = listOf(
        ComboBoxField.LabelledValue(context.i18n.ptrl("All statuses"), ""),
        ComboBoxField.LabelledValue(context.i18n.pnotr("Running"), "running"),
        ComboBoxField.LabelledValue(context.i18n.pnotr("Stopped"), "stopped"),
        ComboBoxField.LabelledValue(context.i18n.pnotr("Failed"), "failed"),
        ComboBoxField.LabelledValue(context.i18n.pnotr("Pending"), "pending"),
    )

    private val templateOptions = MutableStateFlow(listOf(allTemplatesOption()))

    private val filtersField: UiField = context.uiComponents.combobox(
        labelText = context.i18n.pnotr(""),
        values = MutableStateFlow(filterPresetOptions),
        selectedValue = filtersSelection,
    )
    private val templateField: UiField = context.uiComponents.combobox(
        labelText = context.i18n.pnotr(""),
        values = templateOptions,
        selectedValue = templateSelection,
        filterOptions = ComboBoxField.FilterOptions("Search templates"),
    )
    private val statusField: UiField = context.uiComponents.combobox(
        labelText = context.i18n.pnotr(""),
        values = MutableStateFlow(statusOptions),
        selectedValue = statusSelection,
    )

    private val filterControlsGroup = RowGroup(
        RowGroup.RowField(filtersField, RowGroup.RowFieldSettings(weight = 1f)),
        RowGroup.RowField(templateField, RowGroup.RowFieldSettings(weight = 1f)),
        RowGroup.RowField(statusField, RowGroup.RowFieldSettings(weight = 1f)),
    )

    private var syncJob: Job? = null

    private val mutableWorkspaceSearchQuery = MutableStateFlow<String?>(DEFAULT_WORKSPACE_FILTER_QUERY)

    /** The current filter query available to outside components*/
    val workspaceSearchQuery: StateFlow<String?> = mutableWorkspaceSearchQuery

    override val fields: StateFlow<List<UiField>> =
        MutableStateFlow(listOf(workspaceSearchField, filterControlsGroup, errorField))

    override fun beforeShow() {
        syncJob?.cancel()
        syncJob = context.cs.launch(CoroutineName("Workspace Filter Header")) {
            reloadTemplates()

            // 1. Trigger workspace retrieve
            launch {
                workspaceSearchField.contentState
                    .drop(1)
                    .debounce(WORKSPACE_SEARCH_DEBOUNCE)
                    .map { it.trim().ifBlank { null } }
                    .distinctUntilChanged()
                    .collect { query ->
                        mutableWorkspaceSearchQuery.value = query
                        workspaceRefreshTrigger.send(true)
                        context.logger.info("Workspace filter changed, refreshing workspaces...")
                    }
            }
            // 2. Reflect the query back into the dropdowns
            launch {
                workspaceSearchField.contentState.collect { text ->
                    val terms = text.parseFilterQuery()
                    templateSelection.value = terms[TEMPLATE_KEY] ?: ""
                    statusSelection.value = terms[STATUS_KEY] ?: ""
                    filtersSelection.value = text.presetNameForQuery()
                }
            }
            // 3. Apply dropdown selections back into the query.
            launch {
                filtersSelection.drop(1).collect { name ->
                    if (name == CUSTOM_WORKSPACE_FILTER_NAME) return@collect
                    val preset = WORKSPACE_FILTER_PRESETS.firstOrNull { it.name == name } ?: return@collect
                    setQuery(preset.query)
                }
            }
            launch {
                templateSelection.drop(1).collect { template ->
                    applyFilterTerm(TEMPLATE_KEY, template)
                }
            }
            launch {
                statusSelection.drop(1).collect { status ->
                    applyFilterTerm(STATUS_KEY, status)
                }
            }
        }
    }

    override fun afterHide() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * Resets the filter to the default (the authenticated user's workspaces) for a fresh session.
     */
    fun resetFilter() {
        workspaceSearchField.contentState.value = DEFAULT_WORKSPACE_FILTER_QUERY
        mutableWorkspaceSearchQuery.value = DEFAULT_WORKSPACE_FILTER_QUERY
        reportFilterError(null)
    }

    /**
     * Sets the filter to an arbitrary query, e.g. when a URI targets a workspace owned by a
     * different user. Updates both the visible search field and the backing query so the poll
     * immediately uses the new value.
     */
    fun setFilter(query: String) {
        workspaceSearchField.contentState.value = query
        mutableWorkspaceSearchQuery.value = query
        reportFilterError(null)
    }

    /**
     * Shows the server's validation [message] under the search bar, or clears it when [message] is
     * null. Called by the provider when a workspace query is rejected or succeeds.
     */
    fun reportFilterError(message: String?) {
        errorField.textState.update { context.i18n.pnotr(message ?: "") }
    }

    private fun setQuery(query: String) {
        if (query != workspaceSearchField.contentState.value) {
            workspaceSearchField.contentState.value = query
        }
    }

    /** Sets or removes a single filter term in the search text from a dropdown selection. */
    private fun applyFilterTerm(key: String, value: String) {
        setQuery(workspaceSearchField.contentState.value.withFilterTerm(key, value.ifBlank { null }))
    }

    /**
     * Refreshes the Template dropdown from the deployment. Safe to call before the client is ready
     * (yields just the "All templates" option); the provider calls this again once connected.
     */
    suspend fun reloadTemplates() {
        val templates = runCatching { templatesProvider() }
            .onFailure { context.logger.error(it, "Failed to load templates for the workspace filter") }
            .getOrDefault(emptyList())
        templateOptions.value = listOf(allTemplatesOption()) +
                templates
                    .sortedBy { it.displayName.ifBlank { it.name }.lowercase() }
                    .map {
                        ComboBoxField.LabelledValue(
                            context.i18n.pnotr(it.displayName.ifBlank { it.name }),
                            it.name
                        )
                    }
        context.logger.info("Loaded ${templates.size} templates for the workspace filter")
    }

    private fun allTemplatesOption(): ComboBoxField.LabelledValue<String> =
        ComboBoxField.LabelledValue(context.i18n.ptrl("All templates"), "")
}
