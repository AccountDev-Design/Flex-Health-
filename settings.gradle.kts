pluginManagement {
    repositories {
        google()
        // Espejos de Maven Central: repo.maven.apache.org aplica rate limiting (HTTP 429) en entornos CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Espejos de Maven Central: repo.maven.apache.org aplica rate limiting (HTTP 429) en entornos CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
    }
}

rootProject.name = "FlexHeartMonitor"

include(":core:protocol")
include(":core:engine")
include(":core:lan")
include(":core:android")
include(":wear")
include(":receiver")
