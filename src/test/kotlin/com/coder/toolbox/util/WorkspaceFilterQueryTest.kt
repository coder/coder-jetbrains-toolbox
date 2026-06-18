package com.coder.toolbox.util

import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.Test

class WorkspaceFilterQueryTest {
    @Test
    fun `parse keeps key value pairs, last wins, ignores bare text`() {
        assertEquals(
            mapOf("owner" to "me", "status" to "running"),
            "owner:me bobiverse status:running".parseFilterQuery()
        )
        // quoted multi-word value
        assertEquals(
            mapOf("template" to "Heaven 3"),
            """template:"Heaven 3"""".parseFilterQuery()
        )
        // last occurrence wins
        assertEquals(
            mapOf("owner" to "bob"),
            "owner:me owner:bob".parseFilterQuery()
        )
    }

    @Test
    fun `stringify quotes values with spaces and drops blanks`() {
        assertEquals(
            "owner:me template:\"Heaven 3\"",
            linkedMapOf("owner" to "me", "template" to "Heaven 3", "status" to null).stringifyFilterQuery()
        )
    }

    @Test
    fun `withFilterTerm sets, replaces and removes a single key preserving the rest`() {
        // set a new key (appended)
        assertEquals(
            "status:running template:\"Heaven 3\"",
            "status:running".withFilterTerm("template", "Heaven 3")
        )
        // replace an existing key in place
        assertEquals(
            "status:running template:\"Heaven 1\"",
            "status:running template:\"Heaven 3\"".withFilterTerm("template", "Heaven 1")
        )
        // blank removes the key
        assertEquals(
            "status:running",
            "status:running template:\"Heaven 3\"".withFilterTerm("template", null)
        )
    }

    @Test
    fun `presetNameForQuery resolves presets and falls back to custom`() {
        assertEquals("My workspaces", "owner:me".presetNameForQuery())
        assertEquals("All workspaces", "".presetNameForQuery())
        assertEquals("Running workspaces", "status:running".presetNameForQuery())
        assertEquals(CUSTOM_WORKSPACE_FILTER_NAME, "owner:bob name:bobiverse".presetNameForQuery())
    }
}
