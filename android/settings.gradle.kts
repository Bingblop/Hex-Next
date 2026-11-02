pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HexNext"

// Pure-JVM engine (arsc/frro parsers, pack model, resource mapping). The app depends on it
// with implementation("com.hexnext:engine"); Gradle substitutes this included build.
includeBuild("engine")
include(":app")
