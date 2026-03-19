import org.jetbrains.intellij.pluginRepository.PluginRepositoryFactory
import org.jetbrains.intellij.pluginRepository.model.ProductFamily
import java.io.File
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Resolves the Toolbox plugin install directory for the current OS.
 */
fun getPluginInstallDir(extensionId: String): Path {
    val userHome = System.getProperty("user.home").let { Path.of(it) }
    val pluginsDir = when (OS.current()) {
        OS.WINDOWS -> System.getenv("LOCALAPPDATA")?.let { Path.of(it) } ?: (userHome / "AppData" / "Local")
        // currently this is the location that TBA uses on Linux
        OS.LINUX -> System.getenv("XDG_DATA_HOME")?.let { Path.of(it) } ?: (userHome / ".local" / "share")
        OS.MAC -> userHome / "Library" / "Caches"
        else -> error("Unknown os")
    } / "JetBrains" / "Toolbox" / "plugins"

    return pluginsDir / extensionId
}

/**
 * Dependency name fragments provided by Toolbox at runtime.
 * These should be excluded when packaging plugin dependencies.
 */
val TOOLBOX_PROVIDED_DEPENDENCIES = listOf(
    "kotlin",
    "remote-dev-api",
    "core-api",
    "ui-api",
    "annotations",
    "localization-api",
    "slf4j-api"
)

/**
 * Filters out dependencies that are provided by Toolbox at runtime.
 */
fun filterToolboxProvidedDependencies(files: Set<File>): List<File> =
    files.filterNot { file ->
        TOOLBOX_PROVIDED_DEPENDENCIES.any { file.name.contains(it) }
    }

/**
 * Publishes the plugin to the JetBrains Marketplace.
 */
fun publishToMarketplace(extensionId: String, pluginFile: File) {
    val token = System.getenv("JETBRAINS_MARKETPLACE_PUBLISH_TOKEN")
    if (token.isNullOrBlank()) {
        error(
            "Env. variable `JETBRAINS_MARKETPLACE_PUBLISH_TOKEN` does not exist. " +
                    "Please set the env. variable to a token obtained from the marketplace."
        )
    }

    println("Plugin Marketplace Token: ${token.take(5)}*****")

    val instance = PluginRepositoryFactory.create(
        "https://plugins.jetbrains.com",
        token
    )

    // !!! subsequent updates !!!
    instance.uploader.uploadUpdateByXmlIdAndFamily(
        extensionId,  // do not change
        ProductFamily.TOOLBOX,  // do not change
        pluginFile,  // do not change
        null,  // do not change. Channels will be available later
        "Bug fixes and improvements",
        false
    )
}
