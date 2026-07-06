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
    // xg:device:rayneo:begin
    // Generates + includes :xgglass_rayneo_glass_host under build/ (no manual glass_app module needed)
    id("com.xgglass.rayneo.settings")
    // xg:device:rayneo:end
}

dependencyResolutionManagement {
    // Prefer settings repositories so Flutter/Rokid/Android deps resolve consistently.
    // (Flutter's plugin may add project-level repos; Gradle will warn but settings repos win.)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        val frameFlutterAarRepo = file("__XG_SDK_PATH__/third_party/frame/frame_module/build/host/outputs/repo")
        if (frameFlutterAarRepo.exists()) {
            maven { url = uri(frameFlutterAarRepo) }
        }
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
        // xg:device:partial:begin
        substitute(module("io.github.hkust-spark:xgglass-core")).using(project(":core"))
        substitute(module("io.github.hkust-spark:xgglass-core-android")).using(project(":core-android"))
        // xg:device:rokid:begin
        substitute(module("io.github.hkust-spark:xgglass-device-rokid")).using(project(":device-rokid"))
        // xg:device:rokid:end
        // xg:device:rayneo:begin
        substitute(module("io.github.hkust-spark:xgglass-device-rayneo-installer")).using(project(":device-rayneo-installer"))
        substitute(module("io.github.hkust-spark:xgglass-device-rayneo-runtime")).using(project(":device-rayneo-runtime"))
        // xg:device:rayneo:end
        // xg:device:meta:begin
        substitute(module("io.github.hkust-spark:xgglass-device-meta")).using(project(":device-meta"))
        // xg:device:meta:end
        // xg:device:frame:begin
        substitute(module("io.github.hkust-spark:xgglass-device-frame-embedded")).using(project(":device-frame-embedded"))
        // xg:device:frame:end
        // xg:device:omi:begin
        substitute(module("io.github.hkust-spark:xgglass-device-omi")).using(project(":device-omi"))
        // xg:device:omi:end
        // xg:device:even:begin
        substitute(module("io.github.hkust-spark:xgglass-device-even")).using(project(":device-even"))
        // xg:device:even:end
        // xg:device:inmo:begin
        substitute(module("io.github.hkust-spark:xgglass-device-inmo-runtime")).using(project(":device-inmo-runtime"))
        // xg:device:inmo:end
        // xg:device:simulator:begin
        substitute(module("io.github.hkust-spark:xgglass-device-simulator")).using(project(":device-simulator"))
        // xg:device:simulator:end
        // xg:device:partial:end
        // xg:device:all:begin
        substitute(module("io.github.hkust-spark:xgglass-device-rayneo-runtime")).using(project(":device-rayneo-runtime"))
        substitute(module("io.github.hkust-spark:xgglass-universal-full")).using(project(":universal-full"))
        // xg:device:all:end
    }
}
