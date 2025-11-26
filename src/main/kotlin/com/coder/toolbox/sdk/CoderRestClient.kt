package com.coder.toolbox.sdk

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.convertors.ArchConverter
import com.coder.toolbox.sdk.convertors.InstantConverter
import com.coder.toolbox.sdk.convertors.LoggingConverterFactory
import com.coder.toolbox.sdk.convertors.OSConverter
import com.coder.toolbox.sdk.convertors.UUIDConverter
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.interceptors.CertificateRefreshInterceptor
import com.coder.toolbox.sdk.interceptors.Interceptors
import com.coder.toolbox.sdk.v2.CoderV2RestFacade
import com.coder.toolbox.sdk.v2.models.ApiErrorResponse
import com.coder.toolbox.sdk.v2.models.Appearance
import com.coder.toolbox.sdk.v2.models.BuildInfo
import com.coder.toolbox.sdk.v2.models.CreateWorkspaceBuildRequest
import com.coder.toolbox.sdk.v2.models.Template
import com.coder.toolbox.sdk.v2.models.User
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceBuildReason
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspaceTransition
import com.coder.toolbox.util.ReloadableTlsContext
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * An HTTP client that can make requests to the Coder API.
 *
 * The token can be omitted if some other authentication mechanism is in use.
 */
open class CoderRestClient(
    private val context: CoderToolboxContext,
    val url: URL,
    val token: String?,
    private val pluginVersion: String = "development",
) {
    private lateinit var tlsContext: ReloadableTlsContext
    private lateinit var moshi: Moshi
    private lateinit var httpClient: OkHttpClient
    private lateinit var retroRestClient: CoderV2RestFacade

    lateinit var me: User
    lateinit var buildVersion: String
    lateinit var appName: String

    init {
        setupSession()
    }

    private fun setupSession() {
        moshi =
            Moshi.Builder()
                .add(ArchConverter())
                .add(InstantConverter())
                .add(OSConverter())
                .add(UUIDConverter())
                .build()

        tlsContext = ReloadableTlsContext(context.settingsStore.readOnly().tls)

        val interceptors = buildList {
            if (context.settingsStore.requiresTokenAuth) {
                if (token.isNullOrBlank()) {
                    throw IllegalStateException("Token is required for $url deployment")
                }
                add(Interceptors.tokenAuth(token))
            } else if (context.settingsStore.requiresMTlsAuth && context.settingsStore.tls.certRefreshCommand?.isNotBlank() == true) {
                add(CertificateRefreshInterceptor(context, tlsContext))
            }
            add((Interceptors.userAgent(pluginVersion)))
            add(Interceptors.externalHeaders(context, url))
            add(Interceptors.logging(context))
        }

        httpClient = CoderHttpClientBuilder.build(
            context,
            interceptors,
            tlsContext
        )

        retroRestClient =
            Retrofit.Builder().baseUrl(url.toString()).client(httpClient)
                .addConverterFactory(
                    LoggingConverterFactory.wrap(
                        context,
                        MoshiConverterFactory.create(moshi)
                    )
                )
                .build().create(CoderV2RestFacade::class.java)
    }

    /**
     * Load information about the current user and the build version.
     *
     * @throws [APIResponseException].
     */
    suspend fun initializeSession(): User {
        me = me()
        buildVersion = buildInfo().version
        appName = appearance().applicationName
        return me
    }

    /**
     * Retrieve the current user.
     * @throws [APIResponseException].
     */
    internal suspend fun me(): User {
        val userResponse = retroRestClient.me()
        if (!userResponse.isSuccessful) {
            throw APIResponseException(
                "initializeSession",
                url,
                userResponse.code(),
                userResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(userResponse.body()) {
            "Successful response returned null body or user"
        }
    }

    /**
     * Retrieves the visual dashboard configuration.
     */
    internal suspend fun appearance(): Appearance {
        val appearanceResponse = retroRestClient.appearance()
        if (!appearanceResponse.isSuccessful) {
            throw APIResponseException(
                "initializeSession",
                url,
                appearanceResponse.code(),
                appearanceResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(appearanceResponse.body()) {
            "Successful response returned null body for visual dashboard configuration"
        }
    }

    /**
     * Retrieves the available workspaces created by the user.
     * @throws [APIResponseException].
     */
    suspend fun workspaces(): List<Workspace> {
        val workspacesResponse = retroRestClient.workspaces("owner:me")
        if (!workspacesResponse.isSuccessful) {
            throw APIResponseException(
                "retrieve workspaces",
                url,
                workspacesResponse.code(),
                workspacesResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(workspacesResponse.body()?.workspaces) {
            "Successful response returned null body or workspaces"
        }
    }

    /**
     * Retrieves a workspace with the provided id.
     * @throws [APIResponseException].
     */
    suspend fun workspace(workspaceID: UUID): Workspace {
        val workspaceResponse = retroRestClient.workspace(workspaceID)
        if (!workspaceResponse.isSuccessful) {
            throw APIResponseException(
                "retrieve workspace",
                url,
                workspaceResponse.code(),
                workspaceResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(workspaceResponse.body()) {
            "Successful response returned null body or workspace"
        }
    }

    /**
     * Retrieves resources for the specified workspace.  The workspaces response
     * does not include agents when the workspace is off so this can be used to
     * get them instead, just like `coder config-ssh` does (otherwise we risk
     * removing hosts from the SSH config when they are off).
     * @throws [APIResponseException].
     */
    suspend fun resources(workspace: Workspace): List<WorkspaceResource> {
        val resourcesResponse =
            retroRestClient.templateVersionResources(workspace.latestBuild.templateVersionID)
        if (!resourcesResponse.isSuccessful) {
            throw APIResponseException(
                "retrieve resources for ${workspace.name}",
                url,
                resourcesResponse.code(),
                resourcesResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(resourcesResponse.body()) {
            "Successful response returned null body or workspace resources"
        }
    }

    suspend fun buildInfo(): BuildInfo {
        val buildInfoResponse = retroRestClient.buildInfo()
        if (!buildInfoResponse.isSuccessful) {
            throw APIResponseException(
                "retrieve build information",
                url,
                buildInfoResponse.code(),
                buildInfoResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(buildInfoResponse.body()) {
            "Successful response returned null body or build info"
        }
    }

    /**
     * @throws [APIResponseException].
     */
    private suspend fun template(templateID: UUID): Template {
        val templateResponse = retroRestClient.template(templateID)
        if (!templateResponse.isSuccessful) {
            throw APIResponseException(
                "retrieve template with ID $templateID",
                url,
                templateResponse.code(),
                templateResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(templateResponse.body()) {
            "Successful response returned null body or template"
        }
    }

    /**
     * @throws [APIResponseException].
     */
    @Deprecated(message = "This operation needs to be delegated to the CLI")
    suspend fun startWorkspace(workspace: Workspace): WorkspaceBuild {
        val buildRequest = CreateWorkspaceBuildRequest(
            null,
            WorkspaceTransition.START,
            null,
            WorkspaceBuildReason.JETBRAINS_CONNECTION
        )
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest)
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException(
                "start workspace ${workspace.name}",
                url,
                buildResponse.code(),
                buildResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(buildResponse.body()) {
            "Successful response returned null body or workspace build"
        }
    }

    /**
     */
    suspend fun stopWorkspace(workspace: Workspace): WorkspaceBuild {
        val buildRequest = CreateWorkspaceBuildRequest(null, WorkspaceTransition.STOP)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest)
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException(
                "stop workspace ${workspace.name}",
                url,
                buildResponse.code(),
                buildResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(buildResponse.body()) {
            "Successful response returned null body or workspace build"
        }
    }

    /**
     * @throws [APIResponseException] if issues are encountered during deletion
     */
    suspend fun removeWorkspace(workspace: Workspace) {
        val buildRequest = CreateWorkspaceBuildRequest(null, WorkspaceTransition.DELETE, false)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest)
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException(
                "delete workspace ${workspace.name}",
                url,
                buildResponse.code(),
                buildResponse.parseErrorBody(moshi)
            )
        }
    }

    /**
     * Start the workspace with the latest template version.  Best practice is
     * to STOP a workspace before doing an update if it is started.
     * 1. If the update changes parameters, the old template might be needed to
     *    correctly STOP with the existing parameter values.
     * 2. The agent gets a new ID and token on each START build.  Many template
     *    authors are not diligent about making sure the agent gets restarted
     *    with this information when we do two START builds in a row.
     *  @throws [APIResponseException].
     */
    suspend fun updateWorkspace(workspace: Workspace): WorkspaceBuild {
        val template = template(workspace.templateID)
        val buildRequest =
            CreateWorkspaceBuildRequest(template.activeVersionID, WorkspaceTransition.START)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest)
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException(
                "update workspace ${workspace.name}",
                url,
                buildResponse.code(),
                buildResponse.parseErrorBody(moshi)
            )
        }

        return requireNotNull(buildResponse.body()) {
            "Successful response returned null body or workspace build"
        }
    }

    fun close() {
        httpClient.apply {
            dispatcher.executorService.shutdown()
            connectionPool.evictAll()
            cache?.close()
        }
    }
}

private fun Response<*>.parseErrorBody(moshi: Moshi): ApiErrorResponse? {
    val errorBody = this.errorBody() ?: return null
    return try {
        val adapter = moshi.adapter(ApiErrorResponse::class.java)
        adapter.fromJson(errorBody.string())
    } catch (e: Exception) {
        null
    }
}