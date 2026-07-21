import java.io.File

plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("plugin_version").get()
val mc = providers.gradleProperty("mc").get()
val pluginId = providers.gradleProperty("plugin_id").get()
val pluginName = providers.gradleProperty("plugin_name").get()

// Bytecode target: must be <= the Java AquariusProxy runs on (the `java` channel is Java 21+).
val javaReleaseVersion = 21

java {
    toolchain {
        // Compile JDK must be >= the JDK AquariusProxy was built with (25): compilation runs
        // AquariusProxy's bundled @Plugin annotation processor.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.2b2t.vc/releases")
    maven("https://maven.2b2t.vc/remote")
}

// --- AquariusProxy API jar (compileOnly + annotationProcessor) ----------------
val aquariusJarPath: String = providers.gradleProperty("aquarius_jar")
    .orElse(layout.projectDirectory.file("libs/AquariusProxy.jar").asFile.absolutePath)
    .get()
val aquariusJar = files(aquariusJarPath)

dependencies {
    compileOnly(aquariusJar)
    annotationProcessor(aquariusJar)
    // No shaded runtime deps: we use only JDK (java.net.http) + APIs the proxy already provides
    // (Gson via com.aquarius.Globals.GSON), so the plugin jar stays tiny and conflict-free.
}

val checkAquariusJar = tasks.register("checkAquariusJar") {
    val path = aquariusJarPath
    doFirst {
        if (!File(path).exists()) {
            throw GradleException(
                "\nAquariusProxy API jar not found at:\n  $path\n\n" +
                "Place the AquariusProxy fat jar at libs/AquariusProxy.jar, or point the build at it:\n" +
                "  ./gradlew build -Paquarius_jar=/path/to/AquariusProxy.jar\n"
            )
        }
    }
}

// --- BuildConstants templating ------------------------------------------------
val templateProps = mapOf(
    "version" to version.toString(),
    "mc_version" to mc,
    "plugin_id" to pluginId,
    "maven_group" to group.toString(),
)
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    inputs.properties(templateProps)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expand(templateProps)
    filteringCharset = "UTF-8"
}

sourceSets.named("main") {
    java.srcDir(generateTemplates.map { it.destinationDir })
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(checkAquariusJar)
    options.encoding = "UTF-8"
    options.release = javaReleaseVersion
}

// --- Packaging ----------------------------------------------------------------
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<Jar>("shadowJar") {
    archiveBaseName = pluginName
    archiveClassifier = ""
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// --- Local testing ------------------------------------------------------------
val pluginJar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }

val installPluginToRun = tasks.register<Copy>("installPluginToRun") {
    from(pluginJar)
    into(layout.projectDirectory.dir("run/plugins"))
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run AquariusProxy with this plugin loaded (local testing)"
    dependsOn(installPluginToRun)
    notCompatibleWithConfigurationCache("Interactive proxy console reads System.in")
    classpath = aquariusJar
    mainClass = "com.aquarius.Proxy"
    workingDir = layout.projectDirectory.dir("run").asFile
    standardInput = System.`in`
    jvmArgs = listOf(
        "-Xmx300m",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
    outputs.upToDateWhen { false }
}
