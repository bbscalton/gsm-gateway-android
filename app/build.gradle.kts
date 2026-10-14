import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.neuereatec.gsmgateway"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.neuereatec.gsmgateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    // Keystore details live in signing.properties (not committed).
    val signingProps = Properties()
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.exists()) signingFile.inputStream().use { signingProps.load(it) }
    signingConfigs {
        if (signingFile.exists()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { abortOnError = false; checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("libs/tunnel.aar"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
}
