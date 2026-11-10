plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Stable contract between the APK shell and feature.dex (features downloaded from GitHub).
// Any change here -> bump API_VERSION -> users must install a new APK.
android {
    namespace = "com.zenoue.ccbridge.api"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
