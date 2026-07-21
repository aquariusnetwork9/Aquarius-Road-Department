plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.17.16"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

val mc = providers.gradleProperty("minecraft_version").get()
val yarn = providers.gradleProperty("yarn_mappings").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()

base {
    archivesName = providers.gradleProperty("archives_base_name").get()
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    mappings("net.fabricmc:yarn:$yarn:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    // Standalone mod's OWN explicit fabric-api dependency -- this project loads with or without
    // any utility client (Meteor/RusherHack/LambdaClient), so it can't rely on one of those
    // bringing fabric-api along transitively the way a Meteor addon would.
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.add("-Xlint:deprecation")
}

// --- fabric.mod.json templating -------------------------------------------------------------
val modResourceProps = mapOf(
    "version" to version.toString(),
    "mc_version" to mc,
)
tasks.processResources {
    // filesMatching { expand(...) } captures a raw closure the configuration cache can't
    // serialize (same class of issue plugin-aquarius's generateZenithSources task hit).
    notCompatibleWithConfigurationCache("filesMatching{}.expand{} captures a script closure")
    inputs.properties(modResourceProps)
    filesMatching("fabric.mod.json") {
        expand(modResourceProps)
    }
}

// --- BuildConstants templating (in-code constants fabric.mod.json expansion doesn't cover,
// e.g. a User-Agent string / /ard status output) -- same Copy-task pattern plugin-aquarius uses.
val buildConstantsProps = mapOf(
    "version" to version.toString(),
    "mc_version" to mc,
    "maven_group" to group.toString(),
)
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    inputs.properties(buildConstantsProps)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expand(buildConstantsProps)
    filteringCharset = "UTF-8"
}

sourceSets.named("main") {
    java.srcDir(generateTemplates.map { it.destinationDir })
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateTemplates)
}
