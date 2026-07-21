pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK toolchain if it isn't already installed (matches plugin-aquarius's
    // own settings.gradle.kts) -- avoids committing a machine-specific org.gradle.java.home.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = providers.gradleProperty("archives_base_name").get()
