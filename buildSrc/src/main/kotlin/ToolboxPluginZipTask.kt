import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.bundling.Zip

abstract class ToolboxPluginZipTask : Zip() {

    @get:InputFiles
    abstract val jarFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val extensionJsonFiles: ConfigurableFileCollection

    @get:InputFile
    abstract val dependenciesFile: RegularFileProperty

    @get:InputFiles
    abstract val runtimeDependencies: ConfigurableFileCollection

    @get:InputDirectory
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    abstract val pluginDirectory: Property<String>

    init {
        from(extensionJsonFiles)
        from(dependenciesFile) {
            into(pluginDirectory)
        }
        from(resourcesDir) {
            include("icon.svg", "pluginIcon.svg")
            into(pluginDirectory)
        }
        from(jarFiles) {
            into(pluginDirectory.map { "$it/lib" })
        }
        from(runtimeDependencies) {
            into(pluginDirectory.map { "$it/lib" })
        }
        pluginDirectory.convention("")
    }
}
