plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.immichframe"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    // Version lives in gradle.properties (appVersion=x.y.z); release.ps1 bumps it.
    val appVersion = (project.findProperty("appVersion") as String?) ?: "0.1.0"
    val parts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
    val code = parts.getOrElse(0) { 0 } * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }

    // One key for every build, so updates install over each other. keystore/ is NOT in git: back it up.
    signingConfigs {
        create("frame") {
            storeFile = rootProject.file("keystore/frame.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "dev.immichframe"
        minSdk = 23          // Denver PFF-1027 runs Android 6.0.1
        targetSdk = 34
        versionCode = code
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"madmaxvoltro/immichframe\"")
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("frame") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("frame")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
