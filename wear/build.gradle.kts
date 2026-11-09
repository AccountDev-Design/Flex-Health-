plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Debe coincidir con el applicationId del flavor "mobile" del receptor:
    // Wear Data Layer solo entrega mensajes entre apps con el mismo paquete y firma.
    namespace = "com.flexhealth.heartmonitor.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.flexhealth.heartmonitor"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:lan"))
    implementation(project(":core:android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Versión explícita requerida por registerForActivityResult (lint InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.input)
    implementation(libs.wear.ongoing)
    implementation(libs.health.services.client)
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
    testImplementation(project(":core:engine"))
    testImplementation(libs.kotlinx.coroutines.test)
}
