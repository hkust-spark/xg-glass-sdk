plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.xgglass.buildlogic"
version = "0.0.1"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Needed to compile against Android Gradle Plugin DSL types (LibraryExtension / ApplicationExtension).
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.maven.publish.plugin)
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:${libs.versions.dokka.get()}")
}

gradlePlugin {
    plugins {
        create("xgAndroidLibrary") {
            id = "com.xgglass.android.library"
            implementationClass = "com.xgglass.buildlogic.android.XgGlassAndroidLibraryPlugin"
            displayName = "xg.glass Android library convention"
            description = "Standard Android library defaults for xg.glass modules."
        }
        create("xgAndroidApplication") {
            id = "com.xgglass.android.application"
            implementationClass = "com.xgglass.buildlogic.android.XgGlassAndroidApplicationPlugin"
            displayName = "xg.glass Android application convention"
            description = "Standard Android application defaults for xg.glass modules."
        }
        create("rayneoSettings") {
            id = "com.xgglass.rayneo.settings"
            implementationClass = "com.xgglass.buildlogic.rayneo.XgGlassRayneoSettingsPlugin"
            displayName = "xg.glass RayNeo settings plugin"
            description = "Generates and includes a RayNeo glass-host module in the consumer build."
        }
        create("rayneoApp") {
            id = "com.xgglass.rayneo.app"
            implementationClass = "com.xgglass.buildlogic.rayneo.XgGlassRayneoAppPlugin"
            displayName = "xg.glass RayNeo app plugin"
            description = "Wires the RayNeo host APK build + copy-to-assets pipeline for a phone app."
        }
        create("xgMavenPublish") {
            id = "com.xgglass.maven-publish"
            implementationClass = "com.xgglass.buildlogic.publish.XgGlassMavenPublishPlugin"
            displayName = "xg.glass Maven publishing convention"
            description = "Publishes xg.glass SDK libraries to Maven Central-compatible repositories."
        }
    }
}
