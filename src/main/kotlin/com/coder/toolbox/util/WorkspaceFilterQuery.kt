package com.coder.toolbox.util

/**
 * Helpers for the workspace filter query, mirroring the Coder web dashboard.
 *
 * The search text is treated as an opaque Coder filter query that is sent to the server verbatim;
 * the server is the single source of truth for parsing and validation. These helpers exist only so
 * the filter dropdowns (template, status, presets) can edit individual terms the same way the
 * dashboard does, without ever producing duplicate keys.
 */

private val KEY_VALUE = Regex("""(\w+):"([^"]+)"|(\w+):(\S+)""")

/**
 * Parses this filter query into an ordered key -> value map. The last occurrence of a key wins and
 * bare (non `key:value`) text is ignored, exactly like the dashboard's `parseFilterQuery`.
 */
fun String.parseFilterQuery(): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (match in KEY_VALUE.findAll(this)) {
        val key = match.groupValues[1].ifEmpty { match.groupValues[3] }
        val value = match.groupValues[2].ifEmpty { match.groupValues[4] }
        if (key.isNotEmpty() && value.isNotEmpty()) {
            result[key] = value
        }
    }
    return result
}

/**
 * Serializes this key -> value map back into a filter query, quoting values that contain spaces.
 * Entries with a null or blank value are dropped.
 */
fun Map<String, String?>.stringifyFilterQuery(): String =
    entries
        .filter { !it.value.isNullOrBlank() }
        .joinToString(" ") { (key, value) ->
            if (value!!.contains(" ")) "$key:\"$value\"" else "$key:$value"
        }

/**
 * Returns a copy of this query with [key] set to [value], preserving the other terms and their
 * order. A null or blank [value] removes the key. Mirrors the dashboard merging a single dropdown
 * selection into the existing query.
 */
fun String.withFilterTerm(key: String, value: String?): String {
    val values = LinkedHashMap(parseFilterQuery())
    if (value.isNullOrBlank()) {
        values.remove(key)
    } else {
        values[key] = value
    }
    return values.stringifyFilterQuery()
}

/** A named workspace filter preset, identical to the dashboard's preset menu. */
data class WorkspaceFilterPreset(val name: String, val query: String)

/** The default filter applied on each session: only the authenticated user's workspaces. */
const val DEFAULT_WORKSPACE_FILTER_QUERY = "owner:me"

/** Label shown by the presets dropdown when the current query matches no preset. */
const val CUSTOM_WORKSPACE_FILTER_NAME = "Custom"

/** The preset list shown in the "Filters" dropdown, matching the dashboard order and queries. */
val WORKSPACE_FILTER_PRESETS = listOf(
    WorkspaceFilterPreset("My workspaces", "owner:me"),
    WorkspaceFilterPreset("All workspaces", ""),
    WorkspaceFilterPreset("Running workspaces", "status:running"),
    WorkspaceFilterPreset("Failed workspaces", "status:failed"),
    WorkspaceFilterPreset("Outdated workspaces", "outdated:true"),
    WorkspaceFilterPreset("Shared workspaces", "shared:true"),
    WorkspaceFilterPreset("Dormant workspaces", "dormant:true"),
)

/** The preset name whose query exactly matches this query, or [CUSTOM_WORKSPACE_FILTER_NAME] if none. */
fun String.presetNameForQuery(): String =
    WORKSPACE_FILTER_PRESETS.firstOrNull { it.query == trim() }?.name ?: CUSTOM_WORKSPACE_FILTER_NAME
