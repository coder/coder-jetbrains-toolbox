val toolbox = extensions.create<ToolboxExtension>("toolbox")

val extensionJson by tasks.registering(GenerateExtensionJsonTask::class) {
    extensionId.set(project.group.toString())
    extensionVersion.set(project.version.toString())
    apiVersion.set(toolbox.apiVersion)
    metaName.set(toolbox.pluginName)
    metaDescription.set(toolbox.pluginDescription)
    metaVendor.set(toolbox.pluginVendor)
    metaUrl.set(toolbox.pluginUrl)
    outputFile.set(layout.buildDirectory.file("generated/extension.json"))
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(project.group.toString())
    dependsOn(extensionJson)
    from(extensionJson.get().outputs)
}

val filteredDependencies = configurations.named("compileClasspath").map {
    filterToolboxProvidedDependencies(it.files)
}

val copyPlugin by tasks.registering(InstallToolboxPluginTask::class) {
    dependsOn(tasks.named("generateLicenseReport"))
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(extensionJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    extensionId.set(project.group.toString())
}

/**
 * Useful when doing manual local installation.
 */
val pluginPrettyZip by tasks.registering(ToolboxPluginZipTask::class) {
    archiveBaseName.set(project.name)
    dependsOn(tasks.named("generateLicenseReport"))
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(extensionJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    into(project.group.toString()) // folder like com.coder.toolbox
}

val pluginZip by tasks.registering(ToolboxPluginZipTask::class) {
    dependsOn(tasks.named("generateLicenseReport"))
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(extensionJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    archiveBaseName.set(project.group.toString())
}

val cleanAll by tasks.registering(CleanAllTask::class) {
    dependsOn(tasks.named("clean"))
    extensionId.set(project.group.toString())
}

val publishPlugin by tasks.registering(PublishToolboxPluginTask::class) {
    dependsOn(pluginZip)
    extensionId.set(project.group.toString())
    pluginFile.set(pluginZip.flatMap { it.archiveFile })
}
