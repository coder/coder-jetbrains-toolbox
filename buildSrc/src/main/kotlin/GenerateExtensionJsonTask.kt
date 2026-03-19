import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateExtensionJsonTask : DefaultTask() {

    @get:Input
    abstract val extensionId: Property<String>

    @get:Input
    abstract val extensionVersion: Property<String>

    @get:Input
    abstract val apiVersion: Property<String>

    @get:Input
    abstract val metaName: Property<String>

    @get:Input
    abstract val metaDescription: Property<String>

    @get:Input
    abstract val metaVendor: Property<String>

    @get:Input
    @get:Optional
    abstract val metaUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /**
     * Convenience method to set all extension properties at once.
     */
    fun fromExtension(ext: ExtensionJson) {
        extensionId.set(ext.id)
        extensionVersion.set(ext.version)
        metaName.set(ext.meta.name)
        metaDescription.set(ext.meta.description)
        metaVendor.set(ext.meta.vendor)
        ext.meta.url?.let { metaUrl.set(it) }
    }

    @TaskAction
    fun generate() {
        val ext = ExtensionJson(
            id = extensionId.get(),
            version = extensionVersion.get(),
            meta = ExtensionJsonMeta(
                name = metaName.get(),
                description = metaDescription.get(),
                vendor = metaVendor.get(),
                url = metaUrl.orNull,
            )
        )
        generateExtensionJson(ext, apiVersion.get(), outputFile.get().asFile.toPath())
    }
}
