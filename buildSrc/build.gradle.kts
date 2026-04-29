plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/tbx/toolbox-api")
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("org.jetbrains.intellij.plugins:structure-toolbox:3.321")
    implementation("org.jetbrains.intellij:plugin-repository-rest-client:2.0.51")
}
