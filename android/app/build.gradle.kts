plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.livesound.analyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livesound.analyzer"
        minSdk = 26          // Android 8.0+
        targetSdk = 35       // Galaxy Tab S10 Ultra ships Android 14, updatable to 15+
        versionCode = 1
        versionName = "1.0.0"
    }

    // Optional fixed signing key (set by CI from GitHub secrets) so each new APK installs as an
    // update over the previous one. Without it, release builds use the debug key.
    val keystorePath = System.getenv("LIVESOUND_KEYSTORE")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("sideload") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("LIVESOUND_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LIVESOUND_KEY_ALIAS")
                keyPassword = System.getenv("LIVESOUND_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}
