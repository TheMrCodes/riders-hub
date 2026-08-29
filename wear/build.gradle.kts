import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "at.themrcodes.ridershub.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.themrcodes.ridershub"
        minSdk = 26
        targetSdk = 36
        versionCode = 600_019
        versionName = "0.6.0"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    buildFeatures {
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
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")

    implementation(composeBom)
    implementation(project(":wear-shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.fragment:fragment:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.wear.compose:compose-ui-tooling:1.6.2")

    testImplementation("junit:junit:4.13.2")
}
