import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.xgglass.maven-publish")
}

apply(plugin = "com.android.library")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }

    val xcf = XCFramework("XgGlassKit")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "XgGlassKit"
            isStatic = true
            export(project(":core"))
            export(project(":device-simulator-ios"))
            export(project(":device-omi-ios"))
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Re-exports kotlinx-coroutines-core transitively for CoroutineScope in UniversalAppContext.
            api(project(":core"))
        }
        iosMain.dependencies {
            // The iOS framework aggregates the shared API and available iOS device adapters.
            api(project(":device-simulator-ios"))
            api(project(":device-omi-ios"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
    namespace = "com.xgglass.appcontract"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications.withType(MavenPublication::class.java).configureEach {
            if (name == "androidRelease") {
                artifactId = "app-contract"
            }
        }
    }
}

tasks.withType(PublishToMavenLocal::class.java).configureEach {
    onlyIf { publication.name == "androidRelease" }
}

tasks.withType(PublishToMavenRepository::class.java).configureEach {
    onlyIf { publication.name == "androidRelease" }
}
