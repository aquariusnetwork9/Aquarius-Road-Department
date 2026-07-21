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

// Bytecode target: must be <= the Java version ZenithProxy runs on (the `java` channel is Java 21+).
val javaReleaseVersion = 21

java {
    toolchain {
        // Compile JDK must be >= the JDK ZenithProxy was built with (25): compilation runs
        // ZenithProxy's bundled @Plugin annotation processor.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.2b2t.vc/releases")
    maven("https://maven.2b2t.vc/remote")
}

// --- ZenithProxy API jar (compileOnly + annotationProcessor) ------------------
val zenithJarPath: String = providers.gradleProperty("zenith_jar")
    .orElse(layout.projectDirectory.file("libs/ZenithProxy.jar").asFile.absolutePath)
    .get()
val zenithJar = files(zenithJarPath)

dependencies {
    compileOnly(zenithJar)
    annotationProcessor(zenithJar)
}

val checkZenithJar = tasks.register("checkZenithJar") {
    val path = zenithJarPath
    doFirst {
        if (!File(path).exists()) {
            throw GradleException(
                "\nZenithProxy API jar not found at:\n  $path\n\n" +
                "Place the stock ZenithProxy fat jar at libs/ZenithProxy.jar, or point the build at it:\n" +
                "  ./gradlew build -Pzenith_jar=/path/to/ZenithProxy.jar\n" +
                "Download it from: https://github.com/rfresh2/ZenithProxy/releases  (asset: ZenithProxy.jar)\n"
            )
        }
    }
}

// --- Generated sources: plugin-aquarius/src IS the single source of truth ------
// This project has no checked-in src/ of its own. `generateZenithSources` copies
// ../plugin-aquarius/src/main/java verbatim into a build/ output dir, rewriting only the
// AquariusProxy-fork-specific package prefix and the one branded plugin-entrypoint type back
// to their stock-ZenithProxy names. A fix to the reporting/obstruction logic only ever needs
// to happen once, in plugin-aquarius; this project picks it up on its next build.
//
// Rewrite rules, applied in order (order matters -- see rule 2's comment):
//   1. `com.aquarius.` -> `com.zenith.`          (package prefix; the trailing dot is load-
//      bearing so this does NOT also corrupt this plugin's OWN package,
//      `com.aquariusnetwork.highwayconditions`, which starts with the same seven letters but
//      is not followed by a dot)
//   2. `AquariusProxyPlugin` -> `ZenithProxyPlugin`  (the fork renamed this interface itself,
//      not just its package -- confirmed against zenith-abm-bridge, a real plugin already
//      built against stock ZenithProxy.jar: `com.zenith.plugin.api.ZenithProxyPlugin`).
//      Must run AFTER rule 1, since rule 1 alone would leave a dangling
//      `com.zenith.plugin.api.AquariusProxyPlugin` (package fixed, class name still wrong).
// Everything else plugin-aquarius imports (Globals, event.client.*, module.api.Module,
// util.timer.*, mc.block.*, command.api.*, command.brigadier.*, discord.Embed) is a pure
// package rename with no class-name change, per the fork's own history (a straight
// com.zenith -> com.aquarius rename, see the repo's own [[aquariusproxy-fork-and-integration]]
// notes) -- confirmed as far as zenith-abm-bridge's own usage overlaps; anything NOT covered
// by that overlap is verified for real by this project's own build (a compile error here means
// a rule is missing, not a silent wrong jar).
val aquariusSrcDir = layout.projectDirectory.dir("../plugin-aquarius/src/main/java")
val aquariusTemplatesDir = layout.projectDirectory.dir("../plugin-aquarius/src/main/templates")
val generatedJavaDir = layout.buildDirectory.dir("generated/sources/zenith-port/java/main")

val generateZenithSources = tasks.register("generateZenithSources") {
    // The rewrite closure below reads project-layout objects captured from the enclosing
    // script, which the configuration cache can't serialize (script object references) --
    // same class of issue the `run` task below opts out for. This task is a cheap, fast
    // source-text rewrite; re-running it every invocation costs nothing worth chasing a
    // custom-task-class rewrite for.
    notCompatibleWithConfigurationCache("copies/rewrites plugin-aquarius sources via captured script objects")
    inputs.dir(aquariusSrcDir)
    outputs.dir(generatedJavaDir)
    doLast {
        val srcDir = aquariusSrcDir.asFile
        if (!srcDir.isDirectory) {
            throw GradleException(
                "plugin-aquarius source not found at:\n  $srcDir\n\n" +
                "plugin-zenith has no source of its own -- it generates from plugin-aquarius/src/main/java. " +
                "Make sure this is a full checkout of the monorepo, not a sparse/partial one."
            )
        }
        val outDir = generatedJavaDir.get().asFile
        outDir.deleteRecursively()
        var count = 0
        srcDir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { src ->
            val relative = src.relativeTo(srcDir)
            val dest = File(outDir, relative.path)
            dest.parentFile.mkdirs()
            val rewritten = src.readText(Charsets.UTF_8)
                .replace("com.aquarius.", "com.zenith.")
                .replace("AquariusProxyPlugin", "ZenithProxyPlugin")
            dest.writeText(rewritten, Charsets.UTF_8)
            count++
        }
        if (count == 0) {
            throw GradleException("No .java files found under $srcDir -- refusing to produce an empty plugin jar.")
        }
        logger.lifecycle("generateZenithSources: rewrote $count file(s) from plugin-aquarius/src/main/java")
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
    from(aquariusTemplatesDir)
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expand(templateProps)
    filteringCharset = "UTF-8"
}

sourceSets.named("main") {
    java.srcDir(generateZenithSources.map { generatedJavaDir.get() })
    java.srcDir(generateTemplates.map { it.destinationDir })
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(checkZenithJar)
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
    description = "Run ZenithProxy with this plugin loaded (local testing)"
    dependsOn(installPluginToRun)
    notCompatibleWithConfigurationCache("Interactive proxy console reads System.in")
    classpath = zenithJar
    mainClass = "com.zenith.Proxy"
    workingDir = layout.projectDirectory.dir("run").asFile
    standardInput = System.`in`
    jvmArgs = listOf(
        "-Xmx300m",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
    outputs.upToDateWhen { false }
}
