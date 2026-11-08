plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.flexhealth.heartmonitor.core.android"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:lan"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
