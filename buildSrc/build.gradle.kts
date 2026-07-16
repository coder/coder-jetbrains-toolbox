plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/tbx/toolbox-api")
}

dependencies {
    implementation(libs.jackson.module.kotlin)
    implementation(libs.plugin.structure)
    implementation(libs.marketplace.client)
}
