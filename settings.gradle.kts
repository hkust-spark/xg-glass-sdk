val metaGithubToken = (
    providers.gradleProperty("github_token").orNull
        ?: providers.environmentVariable("GITHUB_TOKEN").orNull
        ?: ""
).trim()
val hasMetaDatAccess = metaGithubToken.isNotEmpty()

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // When embedding the generated Flutter module, Flutter's Gradle plugin will add
    // project-level repositories. FAIL_ON_PROJECT_REPOS would break that workflow.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Flutter engine artifacts
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        // Rokid repo should be scoped to Rokid groups only to avoid hijacking AndroidX resolution.
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

rootProject.name = "xg-glass-sdk"

// Single entry-point artifact for third-party apps (one dependency line).
include(":universal")
include(":core")
include(":core-android")
include(":app-contract")
include(":device-rokid")
if (hasMetaDatAccess) {
    include(":device-meta")
}
include(":device-frame-flutter")
include(":device-rayneo-installer")
include(":device-rayneo-runtime")
include(":device-simulator")
include(":device-simulator-ios")
include(":device-omi")
include(":device-android-xr")

// Keep Gradle module names stable, but place implementations under a dedicated folder.
project(":device-rokid").projectDir = file("devices/device-rokid")
if (hasMetaDatAccess) {
    project(":device-meta").projectDir = file("devices/device-meta")
}
project(":device-frame-flutter").projectDir = file("devices/device-frame-flutter")
project(":device-rayneo-installer").projectDir = file("devices/device-rayneo-installer")
project(":device-rayneo-runtime").projectDir = file("devices/device-rayneo-runtime")
project(":device-simulator").projectDir = file("devices/device-simulator")
project(":device-simulator-ios").projectDir = file("devices/device-simulator-ios")
project(":device-omi").projectDir = file("devices/device-omi")
project(":device-android-xr").projectDir = file("devices/device-android-xr")

// Embed the generated Flutter module as an internal dependency when available.
// This avoids requiring app developers to manually include the Flutter module.
val flutterInclude = file("third_party/frame/frame_module/.android/include_flutter.groovy")
if (flutterInclude.exists()) {
    // Flutter's Gradle plugin expects a host app project (default name ":app").
    // We provide a minimal stub here so the embedded Flutter module can be wired at build time.
    include(":app")
    apply(from = flutterInclude)
    include(":device-frame-embedded")
    project(":device-frame-embedded").projectDir = file("devices/device-frame-embedded")
}
