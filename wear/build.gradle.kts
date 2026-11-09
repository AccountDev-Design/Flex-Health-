plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Firma de release. Si defines un keystore propio (en ~/.gradle/gradle.properties o variables de entorno,
 * NUNCA en el repositorio) se usa; si no, la build release (R8, sin depuración, con perfiles de referencia)
 * se firma con la clave de depuración local para poder instalarla y medir el rendimiento real.
 * Reloj y móvil deben compilarse en la misma máquina: Wear Data Layer exige la misma firma.
 */
fun signingProp(name: String, env: String): String? = providers.gradleProperty(name).orElse(providers.environmentVariable(env)).orNull
val releaseStoreFile = signingProp("flexheart.storeFile", "FLEXHEART_STORE_FILE")

android {
    // Debe coincidir con el applicationId del flavor "mobile" del receptor:
    // Wear Data Layer solo entrega mensajes entre apps con el mismo paquete y firma.
    namespace = "com.flexhealth.heartmonitor.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.flexhealth.heartmonitor"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingProp("flexheart.storePassword", "FLEXHEART_STORE_PASSWORD")
                keyAlias = signingProp("flexheart.keyAlias", "FLEXHEART_KEY_ALIAS")
                keyPassword = signingProp("flexheart.keyPassword", "FLEXHEART_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // Instala los perfiles de referencia (app + bibliotecas) también en instalaciones por adb.
    implementation(libs.androidx.profileinstaller)
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
