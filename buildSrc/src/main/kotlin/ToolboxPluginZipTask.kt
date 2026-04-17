import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.bundling.Zip

abstract class ToolboxPluginZipTask : Zip() {

    @get:InputFiles
    abstract val jarFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val extensionJsonFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val runtimeDependencies: ConfigurableFileCollection

    @get:InputDirectory
    abstract val resourcesDir: DirectoryProperty

    init {
        from(jarFiles)
        from(extensionJsonFiles)
        from(resourcesDir) {
            include("dependencies.json", "icon.svg", "pluginIcon.svg")
        }
        from(runtimeDependencies)
    }
}
