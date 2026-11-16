plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.savetube.plugin"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.savetube.plugin"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
        // yt-dlp ships native Python and ffmpeg builds; only modern phone ABIs are kept for size.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    packaging {
        // The bundled Python/ffmpeg binaries must be extracted to disk to be executable.
        jniLibs.useLegacyPackaging = true
    }
    // The same fixed key as the apps: Android only lets Savetube talk to this plugin if both match.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/saveapps.jks")
            storePassword = "saveapps"
            keyAlias = "saveapps"
            keyPassword = "saveapps"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
