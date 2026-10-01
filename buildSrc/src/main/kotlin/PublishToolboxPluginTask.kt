import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Uploads to the Marketplace, an external side effect that Gradle cannot reproduce from cache")
abstract class PublishToolboxPluginTask : DefaultTask() {

    @get:Input
    abstract val extensionId: Property<String>

    @get:InputFile
    abstract val pluginFile: RegularFileProperty

    @TaskAction
    fun publish() {
        publishToMarketplace(extensionId.get(), pluginFile.get().asFile)
    }
}
