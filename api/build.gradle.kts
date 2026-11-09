plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Giao diện cố định giữa APK (vỏ) và feature.dex (chức năng tải từ GitHub).
// Đổi bất kỳ thứ gì ở đây -> tăng API_VERSION -> phải cài APK mới.
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
