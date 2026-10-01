import org.jetbrains.intellij.pluginRepository.PluginRepositoryFactory
import org.jetbrains.intellij.pluginRepository.model.ProductFamily
import java.io.File
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Resolves the Toolbox plugin install directory for the current OS.
 */
fun getPluginInstallDir(extensionId: String): Path {
    val userHome = Path.of(System.getProperty("user.home"))
    val toolboxDir = when (OS.current()) {
        OS.WINDOWS -> {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: (userHome / "AppData" / "Local")
            localAppData / "JetBrains" / "Toolbox" / "cache"
        }

        OS.LINUX -> {
            val dataHome = System.getenv("XDG_DATA_HOME")
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: (userHome / ".local" / "share")
            dataHome / "JetBrains" / "Toolbox"
        }

        OS.MAC -> userHome / "Library" / "Caches" / "JetBrains" / "Toolbox"
        else -> error("Unknown os")
    }

    return toolboxDir / "plugins" / extensionId
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
