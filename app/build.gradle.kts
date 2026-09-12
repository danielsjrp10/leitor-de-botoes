plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.labelhelper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.labelhelper.app"
        // minSdk 30 (Android 11) é necessário porque a API takeScreenshot()
        // do AccessibilityService só existe a partir dessa versão.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")

    // OCR no próprio aparelho, sem internet, sem enviar nada para servidor.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
