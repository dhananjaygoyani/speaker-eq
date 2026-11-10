import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key lives outside the repo: ~/.android/speaker-eq-release.properties
// (storeFile, storePassword, keyAlias, keyPassword). Without it the release APK is debug-signed.
val signProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.android/speaker-eq-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.dhananjaygoyani.speakereq"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.dhananjaygoyani.speakereq"
        minSdk = 28 // DynamicsProcessing (system-wide EQ + limiter)
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (signProps.isNotEmpty()) create("release") {
            storeFile = file(signProps.getProperty("storeFile"))
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    testImplementation("junit:junit:4.13.2")
}
