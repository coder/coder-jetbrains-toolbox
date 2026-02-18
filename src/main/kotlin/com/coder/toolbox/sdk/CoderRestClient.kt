package com.coder.toolbox.sdk

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.oauth.OAuthTokenResponse
import com.coder.toolbox.oauth.TokenEndpointAuthMethod
import com.coder.toolbox.sdk.convertors.ArchConverter
import com.coder.toolbox.sdk.convertors.InstantConverter
import com.coder.toolbox.sdk.convertors.LoggingConverterFactory
import com.coder.toolbox.sdk.convertors.OSConverter
import com.coder.toolbox.sdk.convertors.UUIDConverter
import com.coder.toolbox.sdk.ex.APIResponseException
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
import com.coder.toolbox.views.state.CoderOAuthSessionContext
import com.coder.toolbox.views.state.hasRefreshToken
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zeroturnaround.exec.ProcessExecutor
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
    private val oauthContext: CoderOAuthSessionContext? = null,
    private val pluginVersion: String = "development",
    private val onTokenRefreshed: (suspend (url: URL, oauthSessionCtx: CoderOAuthSessionContext) -> Unit)? = null
) {
    private lateinit var tlsContext: ReloadableTlsContext
    private lateinit var moshi: Moshi
    private lateinit var httpClient: OkHttpClient
    private lateinit var retroRestClient: CoderV2RestFacade

    private val refreshMutex = Mutex()

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
                val oauthOrApiToken = oauthContext?.tokenResponse?.accessToken ?: token
                if (oauthOrApiToken.isNullOrBlank()) {
                    throw IllegalStateException("OAuth or API token is required for $url deployment")
                }
                add(Interceptors.tokenAuth(oauthOrApiToken))
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
        val userResponse = callWithRetry { retroRestClient.me() }
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
        val appearanceResponse = callWithRetry { retroRestClient.appearance() }
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
        val workspacesResponse = callWithRetry { retroRestClient.workspaces("owner:me") }
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
        val workspaceResponse = callWithRetry { retroRestClient.workspace(workspaceID) }
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
        val resourcesResponse = callWithRetry {
            retroRestClient.templateVersionResources(workspace.latestBuild.templateVersionID)
        }
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
        val buildInfoResponse = callWithRetry { retroRestClient.buildInfo() }
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
        val templateResponse = callWithRetry { retroRestClient.template(templateID) }
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
        val buildResponse = callWithRetry { retroRestClient.createWorkspaceBuild(workspace.id, buildRequest) }
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
        val buildResponse = callWithRetry { retroRestClient.createWorkspaceBuild(workspace.id, buildRequest) }
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
        val buildResponse = callWithRetry { retroRestClient.createWorkspaceBuild(workspace.id, buildRequest) }
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
        val buildResponse = callWithRetry { retroRestClient.createWorkspaceBuild(workspace.id, buildRequest) }
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

    /**
     * Executes a Retrofit call with a retry mechanism specifically for expired certificates.
     */
    private suspend fun <T> callWithRetry(block: suspend () -> Response<T>): Response<T> {
        try {
            val response = block()
            if (response.code() == HttpURLConnection.HTTP_UNAUTHORIZED && oauthContext.hasRefreshToken()) {
                val tokenRefreshed = refreshMutex.withLock {
                    // Check if the token was already refreshed while we were waiting for the lock.
                    if (response.raw().request.header("Authorization") != "Bearer ${oauthContext?.tokenResponse?.accessToken}") {
                        return@withLock true
                    }
                    return@withLock try {
                        context.logger.info("Access token expired, attempting to refresh...")
                        refreshToken()
                        true
                    } catch (e: Exception) {
                        context.logger.error(e, "Failed to refresh access token")
                        false
                    }
                }
                if (tokenRefreshed) {
                    context.logger.info("Retrying request with new token...")
                    return block()
                }
            }
            return response
        } catch (e: Exception) {
            if (context.settingsStore.requiresMTlsAuth && isCertExpired(e)) {
                context.logger.info("Certificate expired detected. Attempting refresh...")
                if (refreshCertificates()) {
                    context.logger.info("Certificates refreshed, retrying the request...")
                    return block()
                }
            }
            throw e
        }
    }

    private suspend fun refreshToken() {
        val requestBuilder = Request.Builder().url(oauthContext!!.tokenEndpoint)
        val formBodyBuilder = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", oauthContext.tokenResponse?.refreshToken!!)

        when (oauthContext.tokenAuthMethod) {
            TokenEndpointAuthMethod.CLIENT_SECRET_BASIC -> {
                requestBuilder.header(
                    "Authorization",
                    Credentials.basic(oauthContext.clientId, oauthContext.clientSecret ?: "")
                )
            }

            TokenEndpointAuthMethod.CLIENT_SECRET_POST -> {
                formBodyBuilder.add("client_id", oauthContext.clientId)
                formBodyBuilder.add("client_secret", oauthContext.clientSecret ?: "")
            }

            else -> {
                formBodyBuilder.add("client_id", oauthContext.clientId)
            }
        }

        val request = requestBuilder.post(formBodyBuilder.build()).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw APIResponseException("refresh token", url, response.code, null)
        }

        val responseBody = response.body?.string()
        val newAuthResponse = moshi.adapter(OAuthTokenResponse::class.java).fromJson(responseBody!!)
        this.oauthContext.tokenResponse = newAuthResponse
        onTokenRefreshed?.invoke(url, oauthContext)
    }


    private fun isCertExpired(e: Exception): Boolean {
        return (e is javax.net.ssl.SSLHandshakeException || e is javax.net.ssl.SSLPeerUnverifiedException) &&
                e.message?.contains("certificate_expired", ignoreCase = true) == true
    }

    private suspend fun refreshCertificates(): Boolean = withContext(Dispatchers.IO) {
        val command = context.settingsStore.readOnly().tls.certRefreshCommand
        if (command.isNullOrBlank()) return@withContext false

        return@withContext try {
            val result = ProcessExecutor()
                .command(command.split(" ").toList())
                .exitValueNormal()
                .readOutput(true)
                .execute()

            if (result.exitValue == 0) {
                context.logger.info("Certificate refresh successful. Reloading TLS and evicting pool.")
                tlsContext.reload()

                // This is the "Magic Fix":
                // It forces OkHttp to close the broken HTTP/2 connection.
                httpClient.connectionPool.evictAll()
                return@withContext true
            } else {
                context.logger.error("Refresh command failed with code ${result.exitValue}")
                false
            }
        } catch (ex: Exception) {
            context.logger.error(ex, "Failed to execute refresh command")
            false
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