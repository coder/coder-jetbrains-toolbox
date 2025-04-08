package com.coder.toolbox.sdk.v2

import com.coder.toolbox.sdk.v2.models.BuildInfo
import com.coder.toolbox.sdk.v2.models.CreateWorkspaceBuildRequest
import com.coder.toolbox.sdk.v2.models.Template
import com.coder.toolbox.sdk.v2.models.User
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspacesResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.UUID

interface CoderV2RestFacade {
    /**
     * Retrieves details about the authenticated user.
     */
    @GET("api/v2/users/me")
    suspend fun me(): Response<User>

    /**
     * Retrieves all workspaces the authenticated user has access to.
     */
    @GET("api/v2/workspaces")
    suspend fun workspaces(
        @Query("q") searchParams: String,
    ): Response<WorkspacesResponse>

    /**
     * Retrieves a workspace with the provided id.
     */
    @GET("api/v2/workspaces/{workspaceID}")
    suspend fun workspace(
        @Path("workspaceID") workspaceID: UUID
    ): Response<Workspace>

    @GET("api/v2/buildinfo")
    suspend fun buildInfo(): Response<BuildInfo>

    /**
     * Queues a new build to occur for a workspace.
     */
    @POST("api/v2/workspaces/{workspaceID}/builds")
    suspend fun createWorkspaceBuild(
        @Path("workspaceID") workspaceID: UUID,
        @Body createWorkspaceBuildRequest: CreateWorkspaceBuildRequest,
    ): Response<WorkspaceBuild>

    @GET("api/v2/templates/{templateID}")
    suspend fun template(
        @Path("templateID") templateID: UUID,
    ): Response<Template>

    @GET("api/v2/templateversions/{templateID}/resources")
    suspend fun templateVersionResources(
        @Path("templateID") templateID: UUID,
    ): Response<List<WorkspaceResource>>
}
