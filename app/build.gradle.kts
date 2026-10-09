plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.deivid22srk.portstore"
    compileSdk = 35

    // O release.yml injeta a versão a partir da tag: -PversionName=1.2.0 -PversionCode=10200.
    // Sem propriedades (build local / build.yml) vale o padrão abaixo.
    val versionNameOverride = providers.gradleProperty("versionName").orNull
    val versionCodeOverride = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

    defaultConfig {
        applicationId = "com.deivid22srk.portstore"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOverride ?: 1
        versionName = versionNameOverride ?: "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Assinatura via VARIÁVEIS DE AMBIENTE (nunca fixas no código):
        //   PORTSTORE_KEYSTORE            -> caminho do .jks decodificado no CI
        //   PORTSTORE_KEYSTORE_PASSWORD   -> senha da keystore (secret KEYSTORE_PASSWORD)
        //   PORTSTORE_KEY_ALIAS           -> alias da chave (secret KEY_ALIAS)
        //   PORTSTORE_KEY_PASSWORD        -> senha da chave (secret KEY_PASSWORD)
        // Sem essas variáveis o release sai sem assinatura (não publicável).
        val keystorePath = System.getenv("PORTSTORE_KEYSTORE")
        val storePass = System.getenv("PORTSTORE_KEYSTORE_PASSWORD")
        val envAlias = System.getenv("PORTSTORE_KEY_ALIAS")
        val envKeyPass = System.getenv("PORTSTORE_KEY_PASSWORD")
        if (!keystorePath.isNullOrBlank() && !storePass.isNullOrBlank() && file(keystorePath).exists()) {
            create("ci") {
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = envAlias ?: "portstore"
                keyPassword = envKeyPass ?: storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ci")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Player de vídeo do YouTube embutido (WebView/IFrame) + UI pronta (controles padrão)
    implementation(libs.androidyoutubeplayer.core)
    implementation(libs.androidyoutubeplayer.custom.ui)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
