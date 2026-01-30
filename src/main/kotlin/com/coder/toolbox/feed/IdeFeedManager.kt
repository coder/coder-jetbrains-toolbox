package com.coder.toolbox.feed

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.plugin.PluginManager
import com.coder.toolbox.sdk.CoderHttpClientBuilder
import com.coder.toolbox.sdk.interceptors.Interceptors
import com.coder.toolbox.util.ReloadableTlsContext
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.jvm.optionals.getOrNull

/**
 * Manages the caching and loading of JetBrains IDE product feeds.
 *
 * This manager handles fetching IDE information from JetBrains data services,
 * caching the results locally, and supporting offline mode.
 *
 * Cache files are stored in platform-specific locations:
 * - macOS: ~/Library/Application Support/JetBrains/Toolbox/plugins/com.coder.toolbox/
 * - Linux: ~/.local/share/JetBrains/Toolbox/plugins/com.coder.toolbox/
 * - Windows: %LOCALAPPDATA%/JetBrains/Toolbox/plugins/com.coder.toolbox/
 */
class IdeFeedManager(
    private val context: CoderToolboxContext,
    feedService: JetBrainsFeedService? = null,
    private val runsInOfflineMode: () -> Boolean = {
        ProcessHandle.current().info().commandLine().getOrNull()?.contains("--offline-mode") ?: false
    }
) {
    private val moshi = Moshi.Builder()
        .add(IdeTypeAdapter())
        .build()

    // Lazy initialization of the feed service
    private val feedService: JetBrainsFeedService by lazy {
        if (feedService != null) return@lazy feedService

        val interceptors = buildList {
            add((Interceptors.userAgent(PluginManager.pluginInfo.version)))
            add(Interceptors.logging(context))
        }
        val okHttpClient = CoderHttpClientBuilder.build(
            context,
            interceptors,
            ReloadableTlsContext(context.settingsStore.readOnly().tls)
        )

        val retrofit = Retrofit.Builder()
            .baseUrl("https://data.services.jetbrains.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val feedApi = retrofit.create(JetBrainsFeedApi::class.java)
        JetBrainsFeedService(context, feedApi)
    }

    private var cachedIdes: List<Ide>? = null

    /**
     * Lazily load the IDE list.
     *
     * This method will only execute once. Subsequent calls will return the cached result.
     *
     * If offline mode is enabled (via --offline-mode arg passed to Toolbox), this will load from local cache files.
     * Otherwise, it will fetch from the remote feeds and save to local cache.
     *
     * @return List of IDE objects from both release and EAP feeds
     */
    suspend fun loadIdes(): List<Ide> {
        // Return cached value if already loaded
        cachedIdes?.let { return it }

        val isOffline = isOfflineMode()
        context.logger.info("Loading IDEs in ${if (isOffline) "offline" else "online"} mode")

        val ides = if (isOffline) {
            loadIdesOffline()
        } else {
            loadIdesOnline()
        }

        cachedIdes = ides
        return ides
    }

    /**
     * Load IDEs from local cache files in offline mode.
     */
    private suspend fun loadIdesOffline(): List<Ide> = withContext(Dispatchers.IO) {
        context.logger.info("Loading IDEs from local cache files")

        val releaseIdes = loadFeedFromFile(getReleaseCachePath())
        val eapIdes = loadFeedFromFile(getEapCachePath())

        val allIdes = releaseIdes + eapIdes
        context.logger.info("Loaded ${allIdes.size} IDEs from cache (${releaseIdes.size} release, ${eapIdes.size} EAP)")

        allIdes
    }

    /**
     * Fetch IDEs from remote feeds and cache them locally.
     */
    private suspend fun loadIdesOnline(): List<Ide> {
        context.logger.info("Fetching IDEs from remote feeds")

        // Fetch from both feeds
        val releaseIdes = try {
            feedService.fetchReleaseFeed()
        } catch (e: Exception) {
            context.logger.warn(e, "Failed to fetch release feed")
            emptyList()
        }

        val eapIdes = try {
            feedService.fetchEapFeed()
        } catch (e: Exception) {
            context.logger.warn(e, "Failed to fetch EAP feed")
            emptyList()
        }

        val allIdes = releaseIdes + eapIdes
        context.logger.info("Fetched ${allIdes.size} IDEs from remote (${releaseIdes.size} release, ${eapIdes.size} EAP)")

        return allIdes
    }

    /**
     * Get the path for the release feed cache file.
     */
    private fun getReleaseCachePath(): Path {
        return Paths.get(context.settingsStore.globalDataDirectory, RELEASE_CACHE_FILE)
    }

    /**
     * Get the path for the EAP feed cache file.
     */
    private fun getEapCachePath(): Path {
        return Paths.get(context.settingsStore.globalDataDirectory, EAP_CACHE_FILE)
    }

    /**
     * Load a list of IDEs from a JSON file.
     *
     * @return List of IDEs, or empty list if the file doesn't exist or can't be read
     */
    private suspend fun loadFeedFromFile(path: Path): List<Ide> = withContext(Dispatchers.IO) {
        try {
            if (!path.exists()) {
                context.logger.info("Cache file does not exist: $path")
                return@withContext emptyList()
            }

            val json = path.readText()
            val listType = Types.newParameterizedType(List::class.java, IdeProduct::class.java)
            val adapter = moshi.adapter<List<IdeProduct>>(listType)
            val ides = (adapter.fromJson(json) ?: emptyList()).flatMap { product ->
                product.releases.mapNotNull { release ->
                    Ide.from(product, release)
                }
            }
            context.logger.info("Loaded ${ides.size} IDEs from $path")
            ides
        } catch (e: Exception) {
            context.logger.warn(e, "Failed to load feed from $path")
            emptyList()
        }
    }

    /**
     * Check if offline mode is enabled via the -Doffline=true system property.
     */
    private fun isOfflineMode(): Boolean {
        return runsInOfflineMode()
    }

    /**
     * Find the best matching IDE based on the provided query criteria.
     *
     * This method filters the loaded IDEs by product code and type, optionally
     * filtering by available builds, then returns the IDE with the highest build.
     *
     * @param productCode The IntelliJ product code (e.g., "RR" for RustRover)
     * @param type The type of IDE release (RELEASE or EAP)
     * @param availableBuilds List of acceptable builds to filter by
     * @return The IDE with the highest build matching the criteria, or null if no match found
     */
    suspend fun findBestMatch(
        productCode: String,
        type: IdeType,
        availableBuilds: List<String>
    ): Ide? {
        val ides = loadIdes()

        return ides
            .asSequence()
            .filter { it.code == productCode }
            .filter { it.type == type }
            .filter { it.build in availableBuilds }
            .maxByOrNull { it.build }
    }

    companion object {
        private const val RELEASE_CACHE_FILE = "release.json"
        private const val EAP_CACHE_FILE = "eap.json"
    }
}
