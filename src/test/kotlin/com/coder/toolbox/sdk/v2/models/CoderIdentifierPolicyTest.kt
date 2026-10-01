package com.coder.toolbox.sdk.v2.models

import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class CoderIdentifierPolicyTest {
    @Test
    fun `owner and workspace policy matches supported backend names`() {
        listOf("a", "A1", "some-Workspace-2", "a".repeat(32)).forEach {
            CoderIdentifierPolicy.requireOwner(it)
            CoderIdentifierPolicy.requireWorkspace(it)
        }
    }

    @Test
    fun `agent policy permits legacy backend underscores`() {
        listOf("a", "Agent-1", "legacy_agent", "a".repeat(64)).forEach {
            CoderIdentifierPolicy.requireAgent(it)
        }
    }

    @Test
    fun `deployment-controlled syntax is rejected`() {
        val unsafeNames = listOf(
            "",
            " leading",
            "trailing ",
            "two words",
            "line\nbreak",
            "line\rbreak",
            "workspace.agent",
            "owner/workspace",
            "-flag",
            "name--other",
            "\$(command)",
            "`command`",
            "name;command",
            "a".repeat(65),
        )

        unsafeNames.forEach { name ->
            listOf<(String) -> Unit>(
                CoderIdentifierPolicy::requireOwner,
                CoderIdentifierPolicy::requireWorkspace,
                CoderIdentifierPolicy::requireAgent,
            ).forEach { validator ->
                assertFailsWith<InvalidCoderIdentifierException> {
                    validator(name)
                }
            }
        }
    }

    @Test
    fun `reserved and oversized backend names are rejected`() {
        listOf("new", "create", "a".repeat(33)).forEach { name ->
            assertFailsWith<InvalidCoderIdentifierException> {
                CoderIdentifierPolicy.requireWorkspace(name)
            }
        }
    }
}
