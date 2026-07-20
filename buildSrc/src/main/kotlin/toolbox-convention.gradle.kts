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
    from(extensionJson.flatMap { it.outputFile })
}

val filteredDependencies = configurations.named("runtimeClasspath").map {
    filterToolboxProvidedDependencies(it.files)
}

val generatedDependenciesJson by tasks.registering(GenerateDependenciesJsonTask::class) {
    dependsOn(tasks.named("generateLicenseReport"))
    licenseReportFile.set(layout.buildDirectory.file("reports/dependency-license/dependencies.json"))
    outputFile.set(layout.buildDirectory.file("generated/dependencies.json"))
    bundledDependencies.from(filteredDependencies)
}

val copyPlugin by tasks.registering(InstallToolboxPluginTask::class) {
    dependsOn(generatedDependenciesJson)
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(extensionJson.flatMap { it.outputFile })
    dependenciesFile.set(generatedDependenciesJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    extensionId.set(project.group.toString())
}

/**
 * Useful when doing manual local installation.
 */
val pluginPrettyZip by tasks.registering(ToolboxPluginZipTask::class) {
    archiveBaseName.set(project.name)
    dependsOn(generatedDependenciesJson)
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(extensionJson.flatMap { it.outputFile })
    dependenciesFile.set(generatedDependenciesJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    into(project.group.toString()) // folder like com.coder.toolbox
}

val pluginZip by tasks.registering(ToolboxPluginZipTask::class) {
    dependsOn(generatedDependenciesJson)
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(extensionJson.flatMap { it.outputFile })
    dependenciesFile.set(generatedDependenciesJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    pluginDirectory.set(project.group.toString())
    archiveBaseName.set(project.group.toString())
}

val validatePluginZip by tasks.registering(ValidateToolboxPluginZipTask::class) {
    dependsOn(pluginZip)
    pluginFile.set(pluginZip.flatMap { it.archiveFile })
    extensionId.set(project.group.toString())
    expectedLibraries.from(tasks.named<Jar>("jar"))
    expectedLibraries.from(filteredDependencies)
    toolboxProvidedDependencyNames.set(TOOLBOX_PROVIDED_DEPENDENCIES)
}

pluginZip.configure {
    finalizedBy(validatePluginZip)
}

val cleanAll by tasks.registering(CleanAllTask::class) {
    dependsOn(tasks.named("clean"))
    extensionId.set(project.group.toString())
}

val publishPlugin by tasks.registering(PublishToolboxPluginTask::class) {
    dependsOn(validatePluginZip)
    extensionId.set(project.group.toString())
    pluginFile.set(pluginZip.flatMap { it.archiveFile })
}
