import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Sync
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Writes to a user-managed Toolbox installation (i.e. a location not managed by Gradle) that may have changed since a prior run")
abstract class InstallToolboxPluginTask : Sync() {

    @get:InputFiles
    abstract val jarFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val extensionJsonFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val runtimeDependencies: ConfigurableFileCollection

    @get:InputDirectory
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    abstract val extensionId: Property<String>

    init {
        from(jarFiles)
        from(extensionJsonFiles)
        from(resourcesDir) {
            include("dependencies.json", "icon.svg", "pluginIcon.svg")
        }
        from(runtimeDependencies)
        into(extensionId.map { getPluginInstallDir(it).toFile() })
    }
}
