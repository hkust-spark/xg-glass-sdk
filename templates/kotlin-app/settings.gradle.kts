val metaGithubToken = (
    providers.gradleProperty("github_token").orNull
        ?: providers.environmentVariable("GITHUB_TOKEN").orNull
        ?: ""
).trim()
val hasMetaDatAccess = metaGithubToken.isNotEmpty()

pluginManagement {
    // Provide xg.glass build logic (RayNeo host generator + wiring plugin)
    // Replaced by xg-glass init: __XG_SDK_PATH__/build-logic
    includeBuild("__XG_SDK_PATH__/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Generates + includes :xgglass_rayneo_glass_host under build/ (no manual glass_app module needed)
    id("com.xgglass.rayneo.settings")
}

dependencyResolutionManagement {
    // Prefer settings repositories so Flutter/Rokid/Android deps resolve consistently.
    // (Flutter's plugin may add project-level repos; Gradle will warn but settings repos win.)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        // Keep Rokid repo scoped; it does not necessarily proxy all AndroidX artifacts.
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
            }
            filter {
                includeGroupByRegex("com\\.rokid(\\..+)?")
            }
        }
        if (hasMetaDatAccess) {
            exclusiveContent {
                forRepository {
                    maven {
                        url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                        credentials {
                            username = ""
                            password = metaGithubToken
                        }
                    }
                }
                filter {
                    includeGroupByRegex("com\\.meta\\.wearable(\\..+)?")
                }
            }
        }
    }
}

rootProject.name = "xg-glass-app"
include(":app")
include(":xgglass_app_logic")

// Use the xg.glass SDK as a composite build (no publishing step required).
// Replaced by xg-glass init: __XG_SDK_PATH__
includeBuild("__XG_SDK_PATH__") {
    dependencySubstitution {
        substitute(module("io.github.hkust-spark:xgglass-app-contract")).using(project(":app-contract"))
        substitute(module("io.github.hkust-spark:xgglass-device-rayneo-runtime")).using(project(":device-rayneo-runtime"))
        substitute(module("io.github.hkust-spark:xgglass-universal-full")).using(project(":universal-full"))
    }
}
