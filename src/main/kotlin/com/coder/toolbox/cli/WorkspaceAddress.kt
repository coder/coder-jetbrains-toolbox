package com.coder.toolbox.cli

import com.coder.toolbox.sdk.v2.models.CoderIdentifierPolicy
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent

/** A validated workspace address used at CLI and SSH configuration boundaries. */
internal class WorkspaceAddress private constructor(
    val owner: String,
    val wsName: String,
    val agentName: String?,
) {
    /** Workspace reference: `owner/workspace`. */
    val ownerAndWsName: String = "$owner/$wsName"

    /** Workspace-agent reference: `owner/workspace.agent`. */
    val ownerWsAndAgentName: String
        get() = "$ownerAndWsName.${requireNotNull(agentName)}"

    fun wildcardSshHostAlias(host: String): String =
        requireValidSshAlias(
            "${wildcardSshHostPrefix(host)}--$owner--$wsName.${requireNotNull(agentName)}"
        )

    fun sshHostAlias(host: String): String =
        requireValidSshAlias(
            "$SSH_HOST_ALIAS_PREFIX--$owner--$wsName.${requireNotNull(agentName)}--$host"
        )

    companion object {
        private const val SSH_HOST_ALIAS_PREFIX = "coder-jetbrains-toolbox"
        private val sshHostAliasPattern = Regex("[A-Za-z0-9._-]{1,512}")

        /** Prefix shared by every wildcard SSH host alias for a deployment. */
        fun wildcardSshHostPrefix(host: String): String =
            requireValidSshAlias("$SSH_HOST_ALIAS_PREFIX-$host")

        fun from(ws: Workspace, agent: WorkspaceAgent? = null): WorkspaceAddress {
            CoderIdentifierPolicy.requireOwner(ws.ownerName)
            CoderIdentifierPolicy.requireWorkspace(ws.name)
            agent?.let { CoderIdentifierPolicy.requireAgent(it.name) }
            return WorkspaceAddress(ws.ownerName, ws.name, agent?.name)
        }

        private fun requireValidSshAlias(value: String): String {
            if (!sshHostAliasPattern.matches(value)) {
                throw IllegalArgumentException("Unable to construct a safe SSH host alias")
            }
            return value
        }
    }
}
