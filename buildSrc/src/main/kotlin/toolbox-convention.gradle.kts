val toolbox = extensions.create<ToolboxExtension>("toolbox")

val generateExtensionJson by tasks.registering(GenerateExtensionJsonTask::class) {
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
    dependsOn(generateExtensionJson)
    from(generateExtensionJson.flatMap { it.outputFile })
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

val installPlugin by tasks.registering(InstallToolboxPluginTask::class) {
    dependsOn(generatedDependenciesJson)
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(generateExtensionJson.flatMap { it.outputFile })
    dependenciesFile.set(generatedDependenciesJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    extensionId.set(project.group.toString())
}

val packagePlugin by tasks.registering(ToolboxPluginZipTask::class) {
    dependsOn(generatedDependenciesJson)
    jarFiles.from(tasks.named<Jar>("jar"))
    extensionJsonFiles.from(generateExtensionJson.flatMap { it.outputFile })
    dependenciesFile.set(generatedDependenciesJson.flatMap { it.outputFile })
    runtimeDependencies.from(filteredDependencies)
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    pluginDirectory.set(project.group.toString())
    archiveBaseName.set(project.group.toString())
}

val validatePluginZip by tasks.registering(ValidateToolboxPluginZipTask::class) {
    dependsOn(packagePlugin)
    pluginFile.set(packagePlugin.flatMap { it.archiveFile })
    extensionId.set(project.group.toString())
    expectedLibraries.from(tasks.named<Jar>("jar"))
    expectedLibraries.from(filteredDependencies)
    toolboxProvidedDependencyNames.set(TOOLBOX_PROVIDED_DEPENDENCIES)
}

packagePlugin.configure {
    finalizedBy(validatePluginZip)
}

val cleanAll by tasks.registering(CleanAllTask::class) {
    dependsOn(tasks.named("clean"))
    extensionId.set(project.group.toString())
}

val publishPlugin by tasks.registering(PublishToolboxPluginTask::class) {
    dependsOn(validatePluginZip)
    extensionId.set(project.group.toString())
    pluginFile.set(packagePlugin.flatMap { it.archiveFile })
}
