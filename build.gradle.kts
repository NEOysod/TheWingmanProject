import groovy.json.JsonParserType
import groovy.json.JsonSlurper
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
}

fun listProperty(name: String, default: List<String> = emptyList()): List<String> =
    providers.gradleProperty(name).orNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

fun checkedJarPath(value: String): String {
    val path = value.replace('\\', '/')
    require(path.isNotBlank() && !path.startsWith('/') && ':' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." } && path.endsWith(".jar")) {
        "mod_info.json jars must be relative .jar paths inside the mod: $value"
    }
    return path
}

// Accept Starsector comments and trailing commas. Groovy's lax parser otherwise treats a trailing
// array comma as an extra value. Quoted strings/comments are matched as whole tokens, not rewritten.
val metadataText = providers.fileContents(layout.projectDirectory.file("mod_info.json")).asText.get()
val metadataJson = Regex("""("(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/)|,(?:\s|//[^\r\n]*|/\*[\s\S]*?\*/)*(?=[}\]])""")
    .replace(metadataText) { if (it.groupValues[1].isEmpty()) "" else it.value }
val metadata = JsonSlurper().setType(JsonParserType.LAX).parseText(metadataJson) as Map<*, *>
val modId = metadata["id"] as? String ?: error("mod_info.json must contain an id")
version = metadata["version"] as? String ?: error("mod_info.json must contain a string version")
val modFolderName = providers.gradleProperty("modFolderName").getOrElse(modId)
require(modFolderName.isNotBlank() && modFolderName !in listOf(".", "..") &&
    modFolderName.none { it == '/' || it == '\\' || it == ':' }) { "modFolderName must be a single directory name" }
require(project.version.toString().isNotBlank() && project.version.toString().none { it == '/' || it == '\\' || it == ':' }) {
    "The mod version must be safe to use in an archive filename"
}
val declaredJars = (metadata["jars"] as? List<*>)?.map {
    checkedJarPath(it as? String ?: error("Every mod_info.json jars entry must be a string"))
} ?: emptyList()
require(declaredJars.isNotEmpty()) { "This Java-mod build requires at least one jars entry in mod_info.json" }
val mainJarPath = checkedJarPath(providers.gradleProperty("modJar").getOrElse(declaredJars.first()))
require(mainJarPath in declaredJars) { "modJar must name a jars entry in mod_info.json" }
val bundledJars = declaredJars.filter { it != mainJarPath }
val usesPatchLib = (metadata["dependencies"] as? List<*>)?.any { (it as? Map<*, *>)?.get("id") == "patchlib" } == true

// A mod installed at <game>/mods/<mod> finds <game>/starsector-core without machine-specific paths.
val coreDir = file(providers.gradleProperty("corePath")
    .orElse(providers.environmentVariable("STARSECTOR_CORE"))
    .getOrElse(file("../../starsector-core").absolutePath))
val modsDir = file(providers.gradleProperty("modsPath")
    .orElse(providers.environmentVariable("STARSECTOR_MODS"))
    .getOrElse(coreDir.parentFile.resolve("mods").absolutePath))
val patchLib = file(providers.gradleProperty("patchLibPath")
    .orElse(providers.environmentVariable("PATCHLIB_API"))
    .getOrElse(coreDir.parentFile.resolve("mods/PatchLib/jars/PatchLibAPI.jar").absolutePath))
val coreJars = fileTree(coreDir) { include("*.jar") }
val seedJar = providers.gradleProperty("seedJar").orNull?.takeIf { it.isNotBlank() }?.let { file(it) }
val extraCompileJars = files(listProperty("compileJars").map { file(it) }, fileTree("libs") { include("*.jar") })
val compileDependencies = files(coreJars, bundledJars.map { file(it) }, extraCompileJars,
    seedJar?.let { listOf(it) } ?: emptyList<File>(), if (patchLib.isFile) listOf(patchLib) else emptyList<File>())
val packageDir = layout.buildDirectory.dir("package/$modFolderName")
val mainJavaDirs = if (file("src/main/java").isDirectory) listOf("src/main/java") else listOf("src")
val testJavaDirs = if (file("src/test/java").isDirectory) listOf("src/test/java") else listOf("test")
val patchIncludes = listProperty("patchSourceIncludes",
    if (usesPatchLib) listOf("**/patch/**/*.java", "**/*Patch.java") else emptyList())

sourceSets {
    named("main") {
        java {
            setSrcDirs(mainJavaDirs)
            exclude(patchIncludes + listProperty("mainSourceExcludes"))
        }
    }
    named("test") {
        java.setSrcDirs(testJavaDirs)
    }
}

dependencies {
    compileOnly(compileDependencies)
    testImplementation(compileDependencies)
    testImplementation(sourceSets.main.get().output)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Capture values, not the build script, in execution-time actions.
    val coreDirectory = coreDir
    val coreJarFiles = coreJars
    val seedJarFile = seedJar
    val patchLibRequired = usesPatchLib
    val patchLibFile = patchLib
    doFirst {
        require(coreDirectory.isDirectory && coreJarFiles.files.isNotEmpty()) {
            "Starsector core JARs not found at $coreDirectory; set -PcorePath=... or STARSECTOR_CORE"
        }
        require(seedJarFile == null || seedJarFile.isFile) { "Configured seedJar does not exist: $seedJarFile" }
        require(!patchLibRequired || patchLibFile.isFile) {
            "PatchLib API not found at $patchLibFile; set -PpatchLibPath=... or PATCHLIB_API"
        }
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(providers.gradleProperty("javaRelease").map { it.toInt() }.orElse(8))
}

val compilePatches = tasks.register<JavaCompile>("compilePatches") {
    description = "Compiles separately targeted patch sources, when configured."
    source(if (patchIncludes.isEmpty()) emptyList<Any>() else mainJavaDirs.map { dir ->
        fileTree(dir) { patchIncludes.forEach { include(it) } }
    })
    classpath = sourceSets.main.get().output + compileDependencies
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/patches"))
    options.release.set(providers.gradleProperty("patchJavaRelease").map { it.toInt() }.orElse(17))
}

tasks.named<Test>("test") {
    // Main-method test suites may be invoked separately; do not fail merely because there is no JUnit suite.
    failOnNoDiscoveredTests = false
}

val modJar = tasks.named<Jar>("jar") {
    dependsOn(compilePatches)
    // Java already includes main outputs. Put patch outputs before an optional upstream overlay.
    from(compilePatches.map { it.destinationDirectory })
    if (seedJar != null) {
        from(zipTree(seedJar)) {
            exclude(listProperty("seedExcludes"))
            exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        }
    }
    archiveFileName.set(mainJarPath.substringAfterLast('/'))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val runtimeIncludes = listOf("*.json", "*.version", "README*", "CHANGELOG*", "CHANGES*", "RELEASE_NOTES*",
    "LICENSE*", "NOTICE*", "data/**", "graphics/**", "sounds/**", "music/**") + listProperty("extraPackageIncludes")

val packageMod = tasks.register<Sync>("packageMod") {
    description = "Stages runtime resources and the JARs declared in mod_info.json."
    dependsOn(modJar)
    into(packageDir)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(projectDir) { include(runtimeIncludes) }
    from("resources") { include(runtimeIncludes) }
    from(modJar) { into(mainJarPath.substringBeforeLast('/', "")) }
    for (path in bundledJars) {
        from(file(path)) { into(path.substringBeforeLast('/', "")) }
    }
    val runtimeJarFiles = bundledJars.associateWith { file(it) }
    doFirst {
        for ((path, jarFile) in runtimeJarFiles) require(jarFile.isFile) { "Declared runtime JAR does not exist: $path" }
    }
}

val modZip = tasks.register<Zip>("modZip") {
    group = "distribution"
    description = "Packages the complete mod directory as an installable ZIP."
    dependsOn(packageMod)
    from(packageDir) { into(modFolderName) }
    archiveFileName.set("$modFolderName-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.register<Copy>("installMod") {
    group = "distribution"
    description = "Builds and extracts the mod ZIP into the configured Starsector mods directory."
    dependsOn(modZip)
    doNotTrackState("The destination contains user-managed mods; do not snapshot the entire mods directory.")
    from(zipTree(modZip.flatMap { it.archiveFile }))
    into(modsDir)
    val installDirectory = modsDir
    val sourceDirectory = projectDir
    val installedFolderName = modFolderName
    doFirst {
        require(installDirectory.isDirectory) { "Starsector mods directory does not exist: $installDirectory; set -PmodsPath=..." }
        require(installDirectory.canonicalFile != sourceDirectory.canonicalFile &&
            installDirectory.resolve(installedFolderName).canonicalFile != sourceDirectory.canonicalFile) {
            "installMod would overwrite the source project; choose a different -PmodsPath=..."
        }
    }
    // Copy, not Sync: never remove other mods or files absent from this ZIP.
}

tasks.named("build") {
    dependsOn(modZip)
}
