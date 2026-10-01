import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

abstract class ValidateToolboxPluginZipTask : DefaultTask() {

    @get:InputFile
    abstract val pluginFile: RegularFileProperty

    @get:InputFiles
    abstract val expectedLibraries: ConfigurableFileCollection

    @get:Input
    abstract val toolboxProvidedDependencyNames: ListProperty<String>

    @TaskAction
    fun validate() {
        val expectedResources = setOf("dependencies.json", "icon.svg", "pluginIcon.svg")

        ZipFile(pluginFile.get().asFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            val missingEntries = buildSet {
                if ("extension.json" !in entries) add("extension.json")
                expectedResources.filterNot(entries::contains).forEach(::add)
                expectedLibraries.files
                    .map { it.name }
                    .filterNot(entries::contains)
                    .forEach(::add)
            }
            check(missingEntries.isEmpty()) {
                "Plugin ZIP is missing required entries: ${missingEntries.sorted().joinToString()}"
            }

            val nestedEntries = entries.filter { '/' in it }
            check(nestedEntries.isEmpty()) {
                "Plugin ZIP must use the flat Toolbox plugin layout; found nested entries: ${
                    nestedEntries.sorted().joinToString()
                }"
            }

            val bundledToolboxDependencies = entries.filter { entry ->
                entry.endsWith(".jar") && toolboxProvidedDependencyNames.get().any(entry::contains)
            }
            check(bundledToolboxDependencies.isEmpty()) {
                "Plugin ZIP must not bundle Toolbox-provided dependencies: ${
                    bundledToolboxDependencies.sorted().joinToString()
                }"
            }
        }
    }
}
