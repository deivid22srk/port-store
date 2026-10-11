plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.deivid22srk.portstore.virtual"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Motor de virtualização — referenciado APENAS aqui; o resto do app
    // enxerga somente a interface VirtualEngine/VirtualManager deste módulo.
    implementation(project(":Bcore"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
