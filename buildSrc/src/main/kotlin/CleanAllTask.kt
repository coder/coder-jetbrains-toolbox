import org.gradle.api.provider.Property
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Input

abstract class CleanAllTask : Delete() {

    @get:Input
    abstract val extensionId: Property<String>

    init {
        delete(extensionId.map { getPluginInstallDir(it).toFile() })
    }
}
