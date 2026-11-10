import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/*
 * All app features. NOT packaged into the APK as regular classes: compiled to feature.dex (task
 * featureDex); the APK bundles one copy as a fallback and downloads newer ones from the `live` branch.
 * Libraries (libadb, jsch, bouncycastle, kotlin) are provided by the APK at runtime -> compileOnly.
 */
android {
    namespace = "com.zenoue.ccbridge.feature"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val hostLibs = listOf(
    "com.github.MuntashirAkon:libadb-android:3.1.1",
    "org.bouncycastle:bcpkix-jdk15to18:1.81",
    "com.github.mwiede:jsch:2.28.8",
)

dependencies {
    compileOnly(project(":api"))
    hostLibs.forEach { compileOnly(it) }

    testImplementation(project(":api"))
    hostLibs.forEach { testImplementation(it) }
    testImplementation("junit:junit:4.13.2")
    // org.json in android.jar is only a stub -> use the real one in unit tests
    testImplementation("org.json:json:20240303")
}

// Feature version = commit count on the branch (CI sets FEATURE_VERSION), shared by live and bundled builds.
val featureVersion = System.getenv("FEATURE_VERSION")?.toIntOrNull() ?: 0
// Notes = commit subject, read from git as UTF-8 (the JVM decodes env vars with the locale -> mojibake)
val featureNotes = if (featureVersion <= 0) "" else runCatching {
    val p = ProcessBuilder("git", "log", "-1", "--pretty=%s").directory(rootDir).start()
    p.inputStream.readBytes().toString(Charsets.UTF_8).trim().also { p.waitFor() }
}.getOrDefault("")
// APK builds only: release tag, so old APKs (<= v1.0.8, which read releases/latest) can find feature.dex
val releaseTag = System.getenv("RELEASE_TAG") ?: ""
val featureOut = layout.buildDirectory.dir("outputs/feature")

/** feature classes.jar -> feature.dex (d8) + feature.json {version, api, sha256, size, file, notes}. */
val featureDex = tasks.register("featureDex") {
    val jarTask = tasks.named("bundleLibRuntimeToJarRelease")
    dependsOn(jarTask)
    val jarFiles = jarTask.map { it.outputs.files }
    val apiFile = rootProject.file("api/src/main/java/com/zenoue/ccbridge/api/Api.kt")
    val sdkDir = android.sdkDirectory
    val buildTools = android.buildToolsVersion
    val compileSdk = android.compileSdk
    val tmpDir = layout.buildDirectory.dir("tmp/featureDex")
    inputs.files(jarFiles)
    inputs.file(apiFile)
    inputs.property("version", featureVersion)
    inputs.property("notes", featureNotes)
    inputs.property("tag", releaseTag)
    outputs.dir(featureOut)
    doLast {
        val jar = jarFiles.get().files.single { it.name.endsWith(".jar") }
        val out = featureOut.get().asFile.apply { deleteRecursively(); mkdirs() }
        val tmp = tmpDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val d8 = File(sdkDir, "build-tools/$buildTools/d8").takeIf { it.exists() }
            ?: File(sdkDir, "build-tools").listFiles()?.map { File(it, "d8") }?.filter { it.exists() }
                ?.maxByOrNull { it.parentFile.name }
            ?: throw GradleException("d8 not found in ${sdkDir}/build-tools")
        val androidJar = File(sdkDir, "platforms/android-$compileSdk/android.jar")
        val proc = ProcessBuilder(
            d8.absolutePath, "--release", "--min-api", "26",
            "--lib", androidJar.absolutePath, "--output", tmp.absolutePath, jar.absolutePath,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) throw GradleException("d8 failed:\n$log")
        val dex = File(tmp, "classes.dex")
        if (File(tmp, "classes2.dex").exists()) throw GradleException("feature does not fit in a single dex file")
        dex.copyTo(File(out, "feature.dex"))
        val bytes = dex.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val api = Regex("const val API_VERSION = (\\d+)").find(apiFile.readText())!!.groupValues[1]
        fun js(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
        File(out, "feature.json").writeText(
            """{"version":$featureVersion,"api":$api,"sha256":"$sha","size":${bytes.size},""" +
                """"file":"feature-$featureVersion.dex","notes":${js(featureNotes)}""" +
                (if (releaseTag.isNotEmpty()) ""","tag":${js(releaseTag)}""" else "") + "}\n"
        )
    }
}
