import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import kotlin.io.path.createDirectories

abstract class GenerateDependenciesJsonTask : DefaultTask() {

    @get:InputFile
    abstract val licenseReportFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:InputFiles
    abstract val bundledDependencies: ConfigurableFileCollection

    @TaskAction
    fun generate() {
        val mapper = jacksonMapperBuilder().enable(SerializationFeature.INDENT_OUTPUT).build()
        val bundledFileNames = bundledDependencies.files.map { it.name }.toSet()
        val reportDependencies = mapper.readTree(licenseReportFile.get().asFile)
            .path("dependencies")
            .map(::toPluginDependency)
        val dependencies = reportDependencies.filter { it.archiveFileName() in bundledFileNames }
        val unreportedDependencies = bundledFileNames - dependencies.map { it.archiveFileName() }.toSet()
        check(unreportedDependencies.isEmpty()) {
            "License report is missing bundled dependencies: ${unreportedDependencies.sorted().joinToString()}"
        }

        outputFile.get().asFile.toPath().parent.createDirectories()
        mapper.writeValue(outputFile.get().asFile, dependencies)
    }

    private fun toPluginDependency(dependency: JsonNode) =
        PluginDependency(
            name = dependency.path("moduleName").asText(),
            version = dependency.path("moduleVersion").asText(),
            url = dependency.nullableText("moduleUrl"),
            license = dependency.nullableText("moduleLicense"),
            licenseUrl = dependency.nullableText("moduleLicenseUrl"),
        )

    private fun JsonNode.nullableText(fieldName: String): String? =
        path(fieldName).takeUnless { it.isMissingNode || it.isNull }?.asText()
}

private data class PluginDependency(
    val name: String,
    val version: String,
    val url: String?,
    val license: String?,
    val licenseUrl: String?,
) {
    fun archiveFileName() = "${name.substringAfter(':')}-$version.jar"
}
