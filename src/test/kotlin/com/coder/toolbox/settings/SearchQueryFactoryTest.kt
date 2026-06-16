package com.coder.toolbox.settings

import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.Test

class SearchQueryFactoryTest {
    @Test
    fun `workspace filter query supports bare name search and quoted multi-word values`() {
        assertEquals("name:bobiverse", SearchQueryFactory.filterQuery("bobiverse"))
        assertEquals("name:\"epsilon eridani\"", SearchQueryFactory.filterQuery("epsilon eridani"))
        assertEquals(
            "owner:bill name:delta-eridani template:\"Heaven 2\" status:running",
            SearchQueryFactory.filterQuery("""owner:bill name:delta-eridani template: "Heaven 2" status: running""")
        )
    }

    @Test
    fun `workspace filter query ignores unsupported keys and empty values`() {
        assertEquals(
            "status:running name:82-eridani",
            SearchQueryFactory.filterQuery("""owner: template:"" status:running unsupported:value :bad 82-eridani""")
        )
    }

    @Test
    fun `workspace search query applies selected owner scope unless owner filter is provided`() {
        assertEquals(
            "owner:me name:delta-eridani",
            SearchQueryFactory.workspaceQuery("delta-eridani", WorkspaceScope.MY_WORKSPACES)
        )
        assertEquals(
            "owner:homer name:epsilon-eridani",
            SearchQueryFactory.workspaceQuery("owner:homer name:epsilon-eridani", WorkspaceScope.MY_WORKSPACES)
        )
        assertEquals(
            "name:82-eridani",
            SearchQueryFactory.workspaceQuery("82-eridani", WorkspaceScope.ALL_WORKSPACES)
        )
        assertEquals(null, SearchQueryFactory.workspaceQuery("   ", WorkspaceScope.ALL_WORKSPACES))
    }

    @Test
    fun `workspace search query intersects non owner filters with both workspace scope selections`() {
        data class TestCase(
            val scope: WorkspaceScope,
            val filterQuery: String,
            val expectedQuery: String?
        )

        listOf(
            TestCase(WorkspaceScope.ALL_WORKSPACES, "name:omicron-eridani", "name:omicron-eridani"),
            TestCase(WorkspaceScope.MY_WORKSPACES, "name:omicron-eridani", "owner:me name:omicron-eridani"),
            TestCase(WorkspaceScope.ALL_WORKSPACES, "template:\"Heaven 1\"", "template:\"Heaven 1\""),
            TestCase(
                WorkspaceScope.MY_WORKSPACES,
                "template:\"Heaven 1\"",
                "owner:me template:\"Heaven 1\""
            ),
            TestCase(WorkspaceScope.ALL_WORKSPACES, "status: running", "status:running"),
            TestCase(WorkspaceScope.MY_WORKSPACES, "status: running", "owner:me status:running"),
            TestCase(WorkspaceScope.ALL_WORKSPACES, "delta eridani", "name:\"delta eridani\""),
            TestCase(
                WorkspaceScope.MY_WORKSPACES,
                "delta eridani",
                "owner:me name:\"delta eridani\""
            )
        ).forEach { test ->
            assertEquals(test.expectedQuery, SearchQueryFactory.workspaceQuery(test.filterQuery, test.scope))
        }
    }

    @Test
    fun `workspace search query owner filter overrides both workspace scope selections`() {
        listOf(
            WorkspaceScope.ALL_WORKSPACES,
            WorkspaceScope.MY_WORKSPACES
        ).forEach { scope ->
            assertEquals(
                "owner:riker name:82-eridani",
                SearchQueryFactory.workspaceQuery("owner:riker name:82-eridani", scope)
            )
        }
    }

    @Test
    fun `string extensions delegate to search query factory`() {
        assertEquals(SearchQueryFactory.filterQuery("epsilon-eridani"), "epsilon-eridani".asWorkspaceFilterQuery())
        assertEquals(
            SearchQueryFactory.workspaceQuery("epsilon-eridani", WorkspaceScope.MY_WORKSPACES),
            "epsilon-eridani".asWorkspaceSearchQuery(WorkspaceScope.MY_WORKSPACES)
        )
    }
}
