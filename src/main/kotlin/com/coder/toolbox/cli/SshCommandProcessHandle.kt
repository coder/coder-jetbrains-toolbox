package com.coder.toolbox.cli

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import kotlin.jvm.optionals.getOrNull

/**
 * Identifies the PID for the SSH Coder command spawned by Toolbox.
 */
class SshCommandProcessHandle(private val ctx: CoderToolboxContext) {

    /**
     * Finds the PID of a Coder (not the proxy command) ssh cmd associated with the specified workspace and agent.
     * Null is returned when no ssh command process was found.
     *
     * Implementation Notes:
     * An iterative DFS approach where we start with Toolbox's direct children, grep the command
     * and if nothing is found we continue with the processes children. Toolbox spawns an ssh command
     * as a separate command which in turns spawns another child for the proxy command.
     */
    fun findByWorkspaceAndAgent(ws: Workspace, agent: WorkspaceAgent): Long? {
        val stack = ArrayDeque<ProcessHandle>(ProcessHandle.current().children().toList())
        while (stack.isNotEmpty()) {
            val processHandle = stack.removeLast()
            val cmdLine = processHandle.info().commandLine().getOrNull()
            ctx.logger.debug("SSH command PID: ${processHandle.pid()} Command: $cmdLine")
            if (cmdLine != null && cmdLine.isSshCommandFor(ws, agent)) {
                ctx.logger.debug("SSH command with PID: ${processHandle.pid()} and Command: $cmdLine matches ${ws.name}.${agent.name}")
                return processHandle.pid()
            } else {
                stack.addAll(processHandle.children().toList())
            }
        }
        return null
    }

    private fun String.isSshCommandFor(ws: Workspace, agent: WorkspaceAgent): Boolean {
        // usage-app is present only in the ProxyCommand
        return !this.contains("--usage-app=jetbrains") && this.contains("${ws.name}.${agent.name}")
    }
}