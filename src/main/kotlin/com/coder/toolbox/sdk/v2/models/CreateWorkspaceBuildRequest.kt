package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class CreateWorkspaceBuildRequest(
    // Use to update the workspace to a new template version.
    @Json(name = "template_version_id") val templateVersionID: UUID?,
    // Use to start, stop and delete the workspace.
    @Json(name = "transition") val transition: WorkspaceTransition,
    @Json(name = "orphan") var orphan: Boolean? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CreateWorkspaceBuildRequest

        if (templateVersionID != other.templateVersionID) return false
        if (transition != other.transition) return false
        if (orphan != other.orphan) return false
        return true
    }

    override fun hashCode(): Int {
        var result = orphan?.hashCode() ?: 0
        result = 31 * result + (templateVersionID?.hashCode() ?: 0)
        result = 31 * result + transition.hashCode()
        return result
    }
}
