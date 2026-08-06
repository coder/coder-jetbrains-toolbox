package com.coder.toolbox.sdk.v2.models

/**
 * Validates deployment-provided identifiers before they can reach local command or SSH config sinks.
 *
 * These patterns are intentionally a little looser than a DNS label. Coder permits uppercase names,
 * and supported older deployments may still return agent names containing underscores. Keeping that
 * backend-compatible alphabet is safe here because none of its characters are structural to a shell
 * command or an SSH config line.
 */
internal object CoderIdentifierPolicy {
    private const val MAX_OWNER_OR_WORKSPACE_LENGTH = 32
    private const val MAX_AGENT_LENGTH = 64

    private val ownerOrWorkspacePattern = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")
    private val agentPattern = Regex("[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*")

    fun requireOwner(value: String) = requireValid(
        value = value,
        kind = "workspace owner name",
        maxLength = MAX_OWNER_OR_WORKSPACE_LENGTH,
        pattern = ownerOrWorkspacePattern,
        reservedNames = setOf("new", "create"),
    )

    fun requireWorkspace(wsName: String) = requireValid(
        value = wsName,
        kind = "workspace name",
        maxLength = MAX_OWNER_OR_WORKSPACE_LENGTH,
        pattern = ownerOrWorkspacePattern,
        reservedNames = setOf("new", "create"),
    )

    fun requireAgent(agentName: String) = requireValid(
        value = agentName,
        kind = "workspace agent name",
        maxLength = MAX_AGENT_LENGTH,
        pattern = agentPattern,
    )

    private fun requireValid(
        value: String,
        kind: String,
        maxLength: Int,
        pattern: Regex,
        reservedNames: Set<String> = emptySet(),
    ) {
        if (value.length !in 1..maxLength || !pattern.matches(value) || value in reservedNames) {
            // Do not reflect a deployment-controlled value into logs or UI error messages.
            throw InvalidCoderIdentifierException("The deployment returned an invalid $kind")
        }
    }
}

internal class InvalidCoderIdentifierException(message: String) : IllegalArgumentException(message)
