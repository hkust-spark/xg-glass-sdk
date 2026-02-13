pluginManagement {
    // Provide Universal Glasses build logic (RayNeo host generator + wiring plugin)
    // Replaced by xg-glass init: __XG_SDK_PATH__/build-logic
    includeBuild("__XG_SDK_PATH__/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Generates + includes :ug_rayneo_glass_host under build/ (no manual glass_app module needed)
    id("com.universalglasses.rayneo.settings")
}

// Ensure local.properties is loaded to access secrets (e.g. github_token)
val localProperties = java.util.Properties().apply {
    val localPropertiesFile = rootDir.resolve("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

dependencyResolutionManagement {
    // Prefer settings repositories so Flutter/Rokid/Android deps resolve consistently.
    // (Flutter's plugin may add project-level repos; Gradle will warn but settings repos win.)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        // Meta Wearables DAT
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "ignored"
                password = System.getenv("GITHUB_TOKEN") ?: localProperties.getProperty("github_token")
            }
        }
        // Keep Rokid repo scoped; it does not necessarily proxy all AndroidX artifacts.
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
            }
            filter {
                includeGroupByRegex("com\\.rokid(\\..+)?")
            }
        }
    }
}

rootProject.name = "xg-glass-app"
include(":app")
include(":ug_app_logic")

// Use universal_glasses as a composite build (no publishing step required).
// Replaced by xg-glass init: __XG_SDK_PATH__
includeBuild("__XG_SDK_PATH__")


