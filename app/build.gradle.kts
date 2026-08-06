plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.millisautosend"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.millisautosend"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "2.1-shizuku-build-fix"
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
