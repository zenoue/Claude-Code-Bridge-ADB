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
    }

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
    // ADB client chạy ngay trong app (giao thức ADB + ghép nối không dây)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // TLS 1.3 + export keying material cho ghép nối cùng máy
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Sinh chứng chỉ X.509 tự ký cho khoá ADB (cùng biến thể BouncyCastle mà libadb dùng để tránh trùng lớp)
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    testImplementation("junit:junit:4.13.2")
}
