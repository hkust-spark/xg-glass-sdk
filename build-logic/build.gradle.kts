plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.universalglasses.buildlogic"
version = "0.0.1"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Needed to compile against Android Gradle Plugin DSL types (LibraryExtension / ApplicationExtension).
    // Keep versions aligned with `universal_glasses/gradle/libs.versions.toml`.
    compileOnly("com.android.tools.build:gradle:8.13.1")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
}

gradlePlugin {
    plugins {
        create("ugAndroidLibrary") {
            id = "com.universalglasses.android.library"
            implementationClass = "com.universalglasses.buildlogic.android.UniversalGlassesAndroidLibraryPlugin"
            displayName = "Universal Glasses Android library convention"
            description = "Standard Android library defaults for Universal Glasses modules."
        }
        create("ugAndroidApplication") {
            id = "com.universalglasses.android.application"
            implementationClass = "com.universalglasses.buildlogic.android.UniversalGlassesAndroidApplicationPlugin"
            displayName = "Universal Glasses Android application convention"
            description = "Standard Android application defaults for Universal Glasses modules."
        }
        create("rayneoSettings") {
            id = "com.universalglasses.rayneo.settings"
            implementationClass = "com.universalglasses.buildlogic.rayneo.UniversalGlassesRayneoSettingsPlugin"
            displayName = "Universal Glasses RayNeo settings plugin"
            description = "Generates and includes a RayNeo glass-host module in the consumer build."
        }
        create("rayneoApp") {
            id = "com.universalglasses.rayneo.app"
            implementationClass = "com.universalglasses.buildlogic.rayneo.UniversalGlassesRayneoAppPlugin"
            displayName = "Universal Glasses RayNeo app plugin"
            description = "Wires the RayNeo host APK build + copy-to-assets pipeline for a phone app."
        }
    }
}
