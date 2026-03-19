import org.gradle.api.provider.Property

abstract class ToolboxExtension {
    abstract val pluginName: Property<String>
    abstract val pluginDescription: Property<String>
    abstract val pluginVendor: Property<String>
    abstract val pluginUrl: Property<String>
    abstract val apiVersion: Property<String>
}
