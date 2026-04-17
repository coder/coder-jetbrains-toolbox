import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import com.jetbrains.plugin.structure.toolbox.ToolboxMeta
import com.jetbrains.plugin.structure.toolbox.ToolboxPluginDescriptor
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

data class ExtensionJsonMeta(
    val name: String,
    val description: String,
    val vendor: String,
    val url: String?,
)

data class ExtensionJson(
    val id: String,
    val version: String,
    val meta: ExtensionJsonMeta,
)

fun generateExtensionJson(extensionJson: ExtensionJson, apiVersion: String, destinationFile: Path) {
    val descriptor = ToolboxPluginDescriptor(
        id = extensionJson.id,
        version = extensionJson.version,
        apiVersion = apiVersion,
        meta = ToolboxMeta(
            name = extensionJson.meta.name,
            description = extensionJson.meta.description,
            vendor = extensionJson.meta.vendor,
            url = extensionJson.meta.url,
        )
    )
    destinationFile.parent.createDirectories()
    destinationFile.writeText(
        jacksonMapperBuilder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build()
            .writeValueAsString(descriptor)
    )
}
