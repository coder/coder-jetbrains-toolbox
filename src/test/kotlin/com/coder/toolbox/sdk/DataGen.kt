package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.v2.models.Template
import com.coder.toolbox.sdk.v2.models.User
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentLifecycleState
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.util.Arch
import com.coder.toolbox.util.OS
import java.util.UUID
import kotlin.random.Random

class DataGen {
    companion object {
        fun resource(
            agentName: String,
            agentId: String,
        ): WorkspaceResource = WorkspaceResource(
            agents =
                listOf(
                    WorkspaceAgent(
                        id = UUID.fromString(agentId),
                        status = WorkspaceAgentStatus.CONNECTED,
                        name = agentName,
                        architecture = Arch.from("amd64"),
                        operatingSystem = OS.from("linux"),
                        directory = null,
                        expandedDirectory = null,
                        lifecycleState = WorkspaceAgentLifecycleState.READY,
                        loginBeforeReady = false,
                    ),
                ),
        )

        fun workspace(
            name: String,
            templateID: UUID = UUID.randomUUID(),
            agents: Map<String, String> = emptyMap(),
        ): Workspace {
            val wsId = UUID.randomUUID()
            return Workspace(
                id = wsId,
                templateID = templateID,
                templateName = "template-name",
                templateDisplayName = "template-display-name",
                templateIcon = "template-icon",
                latestBuild =
                    build(
                        resources = agents.map { resource(it.key, it.value) },
                    ),
                outdated = false,
                name = name,
                ownerName = "owner",
            )
        }

        fun build(
            templateVersionID: UUID = UUID.randomUUID(),
            resources: List<WorkspaceResource> = emptyList(),
        ): WorkspaceBuild = WorkspaceBuild(
            id = UUID.randomUUID(),
            buildNumber = Random.nextInt(),
            templateVersionID = templateVersionID,
            resources = resources,
            status = WorkspaceStatus.RUNNING,
        )

        fun template(): Template = Template(
            id = UUID.randomUUID(),
            activeVersionID = UUID.randomUUID(),
        )

        fun user(): User = User(
            "tester",
        )
    }
}
