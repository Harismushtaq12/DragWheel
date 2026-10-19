plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.harismushtaq12.dragwheel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.harismushtaq12.dragwheel"
        minSdk = 29 // Android 10: saving to Downloads needs no permission
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // One fixed key for the GitHub builds, so each new APK installs over the old one.
        // It is public on purpose (for sideloading only). Use a private key for Google Play.
        create("sideload") {
            storeFile = rootProject.file("keystore/dragwheel-sideload.jks")
            storePassword = "dragwheel-sideload"
            keyAlias = "dragwheel"
            keyPassword = "dragwheel-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Video and audio conversion with the phone's own encoders.
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    // PDF merge / split / rotate. Apache-2.0.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // QR codes. Apache-2.0.
    implementation("com.google.zxing:core:3.5.3")
}
