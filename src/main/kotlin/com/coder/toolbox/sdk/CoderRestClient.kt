package com.coder.toolbox.sdk

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.convertors.ArchConverter
import com.coder.toolbox.sdk.convertors.InstantConverter
import com.coder.toolbox.sdk.convertors.OSConverter
import com.coder.toolbox.sdk.convertors.UUIDConverter
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.CoderV2RestFacade
import com.coder.toolbox.sdk.v2.models.ApiErrorResponse
import com.coder.toolbox.sdk.v2.models.BuildInfo
import com.coder.toolbox.sdk.v2.models.CreateWorkspaceBuildRequest
import com.coder.toolbox.sdk.v2.models.Template
import com.coder.toolbox.sdk.v2.models.User
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceAgent
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceTransition
import com.coder.toolbox.util.CoderHostnameVerifier
import com.coder.toolbox.util.coderSocketFactory
import com.coder.toolbox.util.coderTrustManagers
import com.coder.toolbox.util.getArch
import com.coder.toolbox.util.getHeaders
import com.coder.toolbox.util.getOS
import com.jetbrains.toolbox.api.remoteDev.connection.ProxyAuth
import com.squareup.moshi.Moshi
import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.net.ssl.X509TrustManager

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
    private val settings = context.settingsStore.readOnly()
    private lateinit var moshi: Moshi
    private lateinit var httpClient: OkHttpClient
    private lateinit var retroRestClient: CoderV2RestFacade

    lateinit var me: User
    lateinit var buildVersion: String

    init {
        setupSession()
    }

    fun setupSession() {
        moshi =
            Moshi.Builder()
                .add(ArchConverter())
                .add(InstantConverter())
                .add(OSConverter())
                .add(UUIDConverter())
                .build()

        val socketFactory = coderSocketFactory(settings.tls)
        val trustManagers = coderTrustManagers(settings.tls.caPath)
        var builder = OkHttpClient.Builder()

        if (context.proxySettings.getProxy() != null) {
            context.logger.debug("proxy: ${context.proxySettings.getProxy()}")
            builder.proxy(context.proxySettings.getProxy())
        } else if (context.proxySettings.getProxySelector() != null) {
            context.logger.debug("proxy selector: ${context.proxySettings.getProxySelector()}")
            builder.proxySelector(context.proxySettings.getProxySelector()!!)
        }

        // Note: This handles only HTTP/HTTPS proxy authentication.
        // SOCKS5 proxy authentication is currently not supported due to limitations described in:
        // https://youtrack.jetbrains.com/issue/TBX-14532/Missing-proxy-authentication-settings#focus=Comments-27-12265861.0-0
        builder.proxyAuthenticator { _, response ->
            val proxyAuth = context.proxySettings.getProxyAuth()
            if (proxyAuth == null || proxyAuth !is ProxyAuth.Basic) {
                return@proxyAuthenticator null
            }
            val credentials = Credentials.basic(proxyAuth.username, proxyAuth.password)
            response.request.newBuilder()
                .header("Proxy-Authorization", credentials)
                .build()
        }

        if (context.settingsStore.requireTokenAuth) {
            if (token.isNullOrBlank()) {
                throw IllegalStateException("Token is required for $url deployment")
            }
            builder = builder.addInterceptor {
                it.proceed(
                    it.request().newBuilder().addHeader("Coder-Session-Token", token).build()
                )
            }
        }

        httpClient =
            builder
                .sslSocketFactory(socketFactory, trustManagers[0] as X509TrustManager)
                .hostnameVerifier(CoderHostnameVerifier(settings.tls.altHostname))
                .retryOnConnectionFailure(true)
                .addInterceptor {
                    it.proceed(
                        it.request().newBuilder().addHeader(
                            "User-Agent",
                            "Coder Toolbox/$pluginVersion (${getOS()}; ${getArch()})",
                        ).build(),
                    )
                }
                .addInterceptor {
                    var request = it.request()
                    val headers = getHeaders(url, settings.headerCommand)
                    if (headers.isNotEmpty()) {
                        val reqBuilder = request.newBuilder()
                        headers.forEach { h -> reqBuilder.addHeader(h.key, h.value) }
                        request = reqBuilder.build()
                    }
                    it.proceed(request)
                }
                .build()

        retroRestClient =
            Retrofit.Builder().baseUrl(url.toString()).client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
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
        return me
    }

    /**
     * Retrieve the current user.
     * @throws [APIResponseException].
     */
    suspend fun me(): User {
        val userResponse = retroRestClient.me()
        if (!userResponse.isSuccessful) {
            throw APIResponseException(
                "initializeSession",
                url,
                userResponse.code(),
                userResponse.parseErrorBody(moshi)
            )
        }

        return userResponse.body()!!
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

        return workspacesResponse.body()!!.workspaces
    }

    /**
     * Retrieves a workspace with the provided id.
     * @throws [APIResponseException].
     */
    suspend fun workspace(workspaceID: UUID): Workspace {
        val workspacesResponse = retroRestClient.workspace(workspaceID)
        if (!workspacesResponse.isSuccessful) {
            throw APIResponseException(
                "retrieve workspace",
                url,
                workspacesResponse.code(),
                workspacesResponse.parseErrorBody(moshi)
            )
        }

        return workspacesResponse.body()!!
    }

    /**
     * Maps the available workspaces to the associated agents.
     */
    suspend fun workspacesByAgents(): Set<Pair<Workspace, WorkspaceAgent>> {
        // It is possible for there to be resources with duplicate names so we
        // need to use a set.
        return workspaces().flatMap { ws ->
            when (ws.latestBuild.status) {
                WorkspaceStatus.RUNNING -> ws.latestBuild.resources
                else -> resources(ws)
            }.filter { it.agents != null }.flatMap { it.agents!! }.map {
                ws to it
            }
        }.toSet()
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
        return resourcesResponse.body()!!
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
        return buildInfoResponse.body()!!
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
        return templateResponse.body()!!
    }

    /**
     * @throws [APIResponseException].
     */
    suspend fun startWorkspace(workspace: Workspace): WorkspaceBuild {
        val buildRequest = CreateWorkspaceBuildRequest(null, WorkspaceTransition.START)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest)
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException(
                "start workspace ${workspace.name}",
                url,
                buildResponse.code(),
                buildResponse.parseErrorBody(moshi)
            )
        }
        return buildResponse.body()!!
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
        return buildResponse.body()!!
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
        return buildResponse.body()!!
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