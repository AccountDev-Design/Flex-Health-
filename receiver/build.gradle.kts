plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.flexhealth.heartmonitor.receiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.flexhealth.heartmonitor"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "device"
    productFlavors {
        // Galaxy A55 5G: mismo applicationId que la app del reloj (requisito de Data Layer).
        create("mobile") {
            dimension = "device"
            buildConfigField("String", "DEFAULT_MODE", "\"PHONE_BLUETOOTH\"")
        }
        // Galaxy Tab S10 FE: app independiente, servidor WebSocket local.
        create("tablet") {
            dimension = "device"
            applicationIdSuffix = ".tablet"
            buildConfigField("String", "DEFAULT_MODE", "\"TABLET_WIFI\"")
        }
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
    lint {
        // targetSdk 36 es deliberado: es el nivel cuyo comportamiento está documentado para
        // Wear OS 6 / One UI 8 y verificado en este proyecto (ver DEVICE_COMPATIBILITY.md).
        disable += "OldTargetApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric descarga android-all desde Maven Central: se usa el espejo de Google (evita HTTP 429).
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.systemProperty("robolectric.screenshots.dir", rootProject.layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:engine"))
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
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
