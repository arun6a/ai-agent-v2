plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ai.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ai.agent.v2"
        minSdk = 24
        targetSdk = 34
        versionCode = 95
        versionName = "3.7.4"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // === Core AndroidX ===
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // === Jetpack Compose (BOM-managed) ===
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // === Networking ===
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // === ML Kit — on-device OCR for screen reading ===
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // === WorkManager — reliable background execution for rules ===
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // === NanoHTTPD — mini HTTP server inside the app ===
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // === Navigation Compose ===
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // === DataStore (modern replacement for SharedPreferences) ===
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // === Accompanist (system UI controller) ===
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")
}
