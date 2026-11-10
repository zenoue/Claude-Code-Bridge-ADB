plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zenoue.ccbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zenoue.ccbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "GITHUB_REPO", "\"zenoue/Claude-Code-Bridge-ADB\"")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    // Contract with feature.dex (self-updating features). Do NOT depend on :feature - its classes must
    // stay out of the APK's own dex so the copy downloaded from GitHub is loaded instead of a stale one.
    implementation(project(":api"))

    // The libraries below are used by feature.dex at runtime -> the APK must ship them (changing them = new APK).
    // In-app ADB client (ADB protocol + wireless pairing)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // TLS 1.3 + exported keying material for pairing
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Self-signed X.509 certificate for the ADB key (same BouncyCastle flavour as libadb to avoid duplicate classes)
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // In-app reverse SSH tunnel (localhost.run)
    implementation("com.github.mwiede:jsch:2.28.8")

    testImplementation("junit:junit:4.13.2")
}

/** Packages feature.dex + feature.json (the fallback copy bundled with the APK) into assets. */
abstract class FeatureAssets : DefaultTask() {
    @get:InputFiles abstract val source: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        source.asFileTree.forEach { it.copyTo(File(out, it.name)) }
    }
}

val featureAssets = tasks.register<FeatureAssets>("featureAssets") {
    dependsOn(":feature:featureDex")
    source.from(project(":feature").layout.buildDirectory.dir("outputs/feature"))
}

androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(featureAssets, FeatureAssets::outputDir) }
}
