package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.convertors.ArchConverter
import com.coder.toolbox.sdk.convertors.InstantConverter
import com.coder.toolbox.sdk.convertors.OSConverter
import com.coder.toolbox.sdk.convertors.UUIDConverter
import com.coder.toolbox.sdk.ex.APIResponseException
import com.coder.toolbox.sdk.v2.CoderV2RestFacade
import com.coder.toolbox.sdk.v2.models.BuildInfo
import com.coder.toolbox.sdk.v2.models.CreateWorkspaceBuildRequest
import com.coder.toolbox.sdk.v2.models.Template
import com.coder.toolbox.sdk.v2.models.User
import com.coder.toolbox.sdk.v2.models.Workspace
import com.coder.toolbox.sdk.v2.models.WorkspaceBuild
import com.coder.toolbox.sdk.v2.models.WorkspaceResource
import com.coder.toolbox.sdk.v2.models.WorkspaceTransition
import com.coder.toolbox.settings.CoderSettings
import com.coder.toolbox.settings.CoderSettingsState
import com.coder.toolbox.util.CoderHostnameVerifier
import com.coder.toolbox.util.coderSocketFactory
import com.coder.toolbox.util.coderTrustManagers
import com.coder.toolbox.util.getArch
import com.coder.toolbox.util.getHeaders
import com.coder.toolbox.util.getOS
import com.squareup.moshi.Moshi
import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.HttpURLConnection
import java.net.ProxySelector
import java.net.URL
import java.util.UUID
import javax.net.ssl.X509TrustManager

/**
 * Holds proxy information.
 */
data class ProxyValues(
    val username: String?,
    val password: String?,
    val useAuth: Boolean,
    val selector: ProxySelector,
)

/**
 * An HTTP client that can make requests to the Coder API.
 *
 * The token can be omitted if some other authentication mechanism is in use.
 */
open class CoderRestClient(
    val url: URL,
    val token: String?,
    private val settings: CoderSettings = CoderSettings(CoderSettingsState()),
    private val proxyValues: ProxyValues? = null,
    private val pluginVersion: String = "development",
    existingHttpClient: OkHttpClient? = null,
) {
    private val httpClient: OkHttpClient
    private val retroRestClient: CoderV2RestFacade

    lateinit var me: User
    lateinit var buildVersion: String

    init {
        val moshi =
            Moshi.Builder()
                .add(ArchConverter())
                .add(InstantConverter())
                .add(OSConverter())
                .add(UUIDConverter())
                .build()

        val socketFactory = coderSocketFactory(settings.tls)
        val trustManagers = coderTrustManagers(settings.tls.caPath)
        var builder = existingHttpClient?.newBuilder() ?: OkHttpClient.Builder()

        if (proxyValues != null) {
            builder =
                builder
                    .proxySelector(proxyValues.selector)
                    .proxyAuthenticator { _, response ->
                        if (proxyValues.useAuth && proxyValues.username != null && proxyValues.password != null) {
                            val credentials = Credentials.basic(proxyValues.username, proxyValues.password)
                            response.request.newBuilder()
                                .header("Proxy-Authorization", credentials)
                                .build()
                        } else {
                            null
                        }
                    }
        }

        if (token != null) {
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
     * Authenticate and load information about the current user and the build
     * version.
     *
     * @throws [APIResponseException].
     */
    fun authenticate(): User {
        me = me()
        buildVersion = buildInfo().version
        return me
    }

    /**
     * Retrieve the current user.
     * @throws [APIResponseException].
     */
    fun me(): User {
        val userResponse = retroRestClient.me().execute()
        if (!userResponse.isSuccessful) {
            throw APIResponseException("authenticate", url, userResponse)
        }

        return userResponse.body()!!
    }

    /**
     * Retrieves the available workspaces created by the user.
     * @throws [APIResponseException].
     */
    fun workspaces(): List<Workspace> {
        val workspacesResponse = retroRestClient.workspaces("owner:me").execute()
        if (!workspacesResponse.isSuccessful) {
            throw APIResponseException("retrieve workspaces", url, workspacesResponse)
        }

        return workspacesResponse.body()!!.workspaces
    }

    /**
     * Retrieves all the agent names for all workspaces, including those that
     * are off.  Meant to be used when configuring SSH.
     */
    fun agentNames(workspaces: List<Workspace>): Set<String> {
        // It is possible for there to be resources with duplicate names so we
        // need to use a set.
        return workspaces.flatMap { ws ->
            resources(ws).filter { it.agents != null }.flatMap { it.agents!! }.map {
                "${ws.name}.${it.name}"
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
    fun resources(workspace: Workspace): List<WorkspaceResource> {
        val resourcesResponse =
            retroRestClient.templateVersionResources(workspace.latestBuild.templateVersionID).execute()
        if (!resourcesResponse.isSuccessful) {
            throw APIResponseException("retrieve resources for ${workspace.name}", url, resourcesResponse)
        }
        return resourcesResponse.body()!!
    }

    fun buildInfo(): BuildInfo {
        val buildInfoResponse = retroRestClient.buildInfo().execute()
        if (!buildInfoResponse.isSuccessful) {
            throw APIResponseException("retrieve build information", url, buildInfoResponse)
        }
        return buildInfoResponse.body()!!
    }

    /**
     * @throws [APIResponseException].
     */
    private fun template(templateID: UUID): Template {
        val templateResponse = retroRestClient.template(templateID).execute()
        if (!templateResponse.isSuccessful) {
            throw APIResponseException("retrieve template with ID $templateID", url, templateResponse)
        }
        return templateResponse.body()!!
    }

    /**
     * @throws [APIResponseException].
     */
    fun startWorkspace(workspace: Workspace): WorkspaceBuild {
        val buildRequest = CreateWorkspaceBuildRequest(null, WorkspaceTransition.START)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest).execute()
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException("start workspace ${workspace.name}", url, buildResponse)
        }
        return buildResponse.body()!!
    }

    /**
     */
    fun stopWorkspace(workspace: Workspace): WorkspaceBuild {
        val buildRequest = CreateWorkspaceBuildRequest(null, WorkspaceTransition.STOP)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest).execute()
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException("stop workspace ${workspace.name}", url, buildResponse)
        }
        return buildResponse.body()!!
    }

    /**
     * @throws [APIResponseException].
     */
    fun removeWorkspace(workspace: Workspace) {
        // TODO - implement this
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
    fun updateWorkspace(workspace: Workspace): WorkspaceBuild {
        val template = template(workspace.templateID)
        val buildRequest =
            CreateWorkspaceBuildRequest(template.activeVersionID, WorkspaceTransition.START)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspace.id, buildRequest).execute()
        if (buildResponse.code() != HttpURLConnection.HTTP_CREATED) {
            throw APIResponseException("update workspace ${workspace.name}", url, buildResponse)
        }
        return buildResponse.body()!!
    }
}
