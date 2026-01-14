import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import com.github.jk1.license.filter.ExcludeTransitiveDependenciesFilter
import com.github.jk1.license.render.JsonReportRenderer
import com.jetbrains.plugin.structure.toolbox.ToolboxMeta
import com.jetbrains.plugin.structure.toolbox.ToolboxPluginDescriptor
import org.jetbrains.intellij.pluginRepository.PluginRepositoryFactory
import org.jetbrains.intellij.pluginRepository.model.ProductFamily
import org.jetbrains.kotlin.com.intellij.openapi.util.SystemInfoRt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    `java-library`
    alias(libs.plugins.dependency.license.report)
    alias(libs.plugins.ksp)
    alias(libs.plugins.gradle.wrapper)
    alias(libs.plugins.changelog)
    alias(libs.plugins.gettext)
    alias(libs.plugins.detekt)
}


repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/tbx/toolbox-api")
}

buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath(libs.marketplace.client)
        classpath(libs.plugin.structure)
    }
}

jvmWrapper {
    unixJvmInstallDir = "jvm"
    winJvmInstallDir = "jvm"
    linuxAarch64JvmUrl =
        "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-21.0.5-linux-aarch64-b631.28.tar.gz"
    linuxX64JvmUrl = "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-21.0.5-linux-x64-b631.28.tar.gz"
    macAarch64JvmUrl = "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-21.0.5-osx-aarch64-b631.28.tar.gz"
    macX64JvmUrl = "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-21.0.5-osx-x64-b631.28.tar.gz"
    windowsX64JvmUrl = "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-21.0.5-windows-x64-b631.28.tar.gz"
}

dependencies {
    compileOnly(libs.bundles.toolbox.plugin.api)
    compileOnly(libs.bundles.serialization)
    compileOnly(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.exec)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.bundles.bouncycastle)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mokk)
    testImplementation(libs.bundles.toolbox.plugin.api)
    testImplementation(libs.coroutines.test)
}

val extension = ExtensionJson(
    id = properties("group"),

    version = properties("version"),
    meta = ExtensionJsonMeta(
        name = "Coder",
        description = "Connects your JetBrains IDE to Coder workspaces",
        vendor = "coder",
        url = "https://github.com/coder/coder-jetbrains-toolbox",
    )
)

val extensionJsonFile = layout.buildDirectory.file("generated/extension.json")
val extensionJson by tasks.registering {
    inputs.property("extension", extension.toString())

    outputs.file(extensionJsonFile)
    doLast {
        generateExtensionJson(extension, extensionJsonFile.get().asFile.toPath())
    }
}

changelog {
    version.set(extension.version)
    groups.set(emptyList())
    title.set("Coder Toolbox Plugin Changelog")
}

licenseReport {
    renderers = arrayOf(JsonReportRenderer("dependencies.json"))
    filters = arrayOf(ExcludeTransitiveDependenciesFilter())
    // jq script to convert to our format:
    // `jq '[.dependencies[] | {name: .moduleName, version: .moduleVersion, url: .moduleUrl, license: .moduleLicense, licenseUrl: .moduleLicenseUrl}]' < build/reports/dependency-license/dependencies.json > src/main/resources/dependencies.json`
}

tasks.compileKotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.test {
    useJUnitPlatform()
}

// Detekt configuration for JetBrains compliance and code quality
detekt {
    config.setFrom("$projectDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

// Configure detekt for JetBrains compliance and code quality
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
    // Fail build on detekt issues for JetBrains compliance
    ignoreFailures = false
}


tasks.jar {
    archiveBaseName.set(extension.id)
    dependsOn(extensionJson)
    from(extensionJson.get().outputs)
}

val copyPlugin by tasks.creating(Sync::class.java) {
    dependsOn(tasks.jar)
    dependsOn(tasks.getByName("generateLicenseReport"))

    fromCompileDependencies()
    into(getPluginInstallDir())
}

fun CopySpec.fromCompileDependencies() {
    from(tasks.jar)
    from(extensionJson.get().outputs.files)
    from("src/main/resources") {
        include("dependencies.json")
    }
    from("src/main/resources") {
        include("icon.svg")
        include("pluginIcon.svg")
    }

    // Copy dependencies, excluding those provided by Toolbox.
    from(
        configurations.compileClasspath.map { configuration ->
            configuration.files.filterNot { file ->
                listOf(
                    "kotlin",
                    "remote-dev-api",
                    "core-api",
                    "ui-api",
                    "annotations",
                    "localization-api",
                    "slf4j-api"
                ).any { file.name.contains(it) }
            }
        },
    )
}

/**
 * Useful when doing manual local install.
 */
val pluginPrettyZip by tasks.creating(Zip::class) {
    archiveBaseName.set(properties("name"))
    dependsOn(tasks.jar)
    dependsOn(tasks.getByName("generateLicenseReport"))

    fromCompileDependencies()
    into(extension.id) // folder like com.coder.toolbox
}

val pluginZip by tasks.creating(Zip::class) {
    dependsOn(tasks.jar)
    dependsOn(tasks.getByName("generateLicenseReport"))

    fromCompileDependencies()
    archiveBaseName.set(extension.id)
}

tasks.register("cleanAll", Delete::class.java) {
    dependsOn(tasks.clean)
    delete(getPluginInstallDir())
    delete()
}

private fun getPluginInstallDir(): Path {
    val userHome = System.getProperty("user.home").let { Path.of(it) }
    val toolboxCachesDir = when {
        SystemInfoRt.isWindows -> System.getenv("LOCALAPPDATA")?.let { Path.of(it) } ?: (userHome / "AppData" / "Local")
        // currently this is the location that TBA uses on Linux
        SystemInfoRt.isLinux -> System.getenv("XDG_DATA_HOME")?.let { Path.of(it) } ?: (userHome / ".local" / "share")
        SystemInfoRt.isMac -> userHome / "Library" / "Caches"
        else -> error("Unknown os")
    } / "JetBrains" / "Toolbox"

    val pluginsDir = when {
        SystemInfoRt.isWindows ||
                SystemInfoRt.isLinux ||
                SystemInfoRt.isMac -> toolboxCachesDir

        else -> error("Unknown os")
    } / "plugins"

    return pluginsDir / extension.id
}

val publishPlugin by tasks.registering {
    dependsOn(pluginZip)

    doLast {
        val pluginMarketplaceToken: String = if (System.getenv("JETBRAINS_MARKETPLACE_PUBLISH_TOKEN").isNullOrBlank()) {
            error("Env. variable `JETBRAINS_MARKETPLACE_PUBLISH_TOKEN` does not exist. Please set the env. variable to a token obtained from the marketplace.")
        } else {
            System.getenv("JETBRAINS_MARKETPLACE_PUBLISH_TOKEN")
        }

        println("Plugin Marketplace Token: ${pluginMarketplaceToken.take(5)}*****")

        val instance = PluginRepositoryFactory.create(
            "https://plugins.jetbrains.com",
            pluginMarketplaceToken
        )

        // !!! subsequent updates !!!
        instance.uploader.uploadUpdateByXmlIdAndFamily(
            extension.id,  // do not change
            ProductFamily.TOOLBOX,  // do not change
            pluginZip.outputs.files.singleFile,  // do not change
            null,  // do not change. Channels will be available later
            "Bug fixes and improvements",
            false
        )
    }
}

fun properties(key: String) = project.findProperty(key).toString()

gettext {
    potFile = project.layout.projectDirectory.file("src/main/resources/localization/defaultMessages.pot")
    keywords = listOf("ptrc:1c,2", "ptrl")
}

// region will be moved to the gradle plugin late
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

fun generateExtensionJson(extensionJson: ExtensionJson, destinationFile: Path) {
    val descriptor = ToolboxPluginDescriptor(
        id = extensionJson.id,
        version = extensionJson.version,
        apiVersion = libs.versions.toolbox.plugin.api.get(),
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
// endregion
