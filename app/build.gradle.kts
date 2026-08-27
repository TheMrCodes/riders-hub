import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "at.themrcodes.ridershub"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.themrcodes.ridershub"
        minSdk = 34
        targetSdk = 36
        versionCode = 19
        versionName = "0.6.0"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose 1.11 is the last stable line compatible with API 36 / AGP 8.x.
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Home Assistant mobile_app webhook payload encryption (XSalsa20-Poly1305).
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
}
