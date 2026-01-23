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
    }
}

rootProject.name = "universal-glasses"

// Single entry-point artifact for third-party apps (one dependency line).
include(":universal")
include(":core")
include(":app-contract")
include(":device-rokid")
include(":device-frame-flutter")
include(":device-rayneo-installer")
include(":device-rayneo-runtime")
include(":device-sim-emulator")

// Keep Gradle module names stable, but place implementations under a dedicated folder.
project(":device-rokid").projectDir = file("devices/device-rokid")
project(":device-frame-flutter").projectDir = file("devices/device-frame-flutter")
project(":device-rayneo-installer").projectDir = file("devices/device-rayneo-installer")
project(":device-rayneo-runtime").projectDir = file("devices/device-rayneo-runtime")
project(":device-sim-emulator").projectDir = file("devices/device-sim-emulator")

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


