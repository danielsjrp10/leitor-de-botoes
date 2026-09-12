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
        versionCode = 2
        versionName = "0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // ATENÇÃO - ASSINATURA:
    // Nenhuma senha ou keystore fica neste arquivo. Os quatro valores abaixo
    // só existem como variáveis de ambiente durante a compilação no GitHub
    // Actions, lidas a partir de "Secrets" configurados manualmente pelo
    // dono do repositório (veja o README, seção "Assinatura da build").
    // Sem essas variáveis, a build de release é gerada SEM assinatura
    // (não instalável até ser assinada).
    signingConfigs {
        create("release") {
            val storePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    // OCR local, offline, sem servidor externo e sem IA generativa.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
