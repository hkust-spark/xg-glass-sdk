import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    id("com.xgglass.maven-publish")
}

kotlin {
    androidLibrary {
        namespace = "com.xgglass.appcontract"
        compileSdk = 36
        minSdk = 28
        withHostTestBuilder {}.configure {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
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
            export(project(":device-even"))
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
            api(project(":device-even"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications.withType(MavenPublication::class.java).configureEach {
            if (name == "android") {
                artifactId = "app-contract"
            }
        }
    }
}

tasks.withType(PublishToMavenLocal::class.java).configureEach {
    onlyIf { publication.name == "android" }
}

tasks.withType(PublishToMavenRepository::class.java).configureEach {
    onlyIf { publication.name == "android" }
}
