import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val oidcRedirectUri = "at.themrcodes.ridershub:/oauth2redirect"

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
        versionCode = libs.versions.phoneVersionCode.get().toInt()
        versionName = libs.versions.phoneVersion.get()

        testInstrumentationRunner = "android.app.Instrumentation"
        manifestPlaceholders["appAuthRedirectScheme"] = oidcRedirectUri.substringBefore(':')
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"$oidcRedirectUri\"")
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
    // Play services still references Fragment 1.1 transitively; keep Activity Result compatible.
    implementation("androidx.fragment:fragment:1.9.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(project(":wear-shared"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear:wear-remote-interactions:1.2.0")

    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("net.openid:appauth:0.11.1")
    // AppAuth's old transitive browser pin is overridden with the current AndroidX line.
    implementation("androidx.browser:browser:1.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.3.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
