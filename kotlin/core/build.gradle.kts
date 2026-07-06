import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    id("com.xgglass.maven-publish")
}

dokka {
    moduleName.set("xgglass-core")
}

apply(plugin = "com.android.library")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
    namespace = "com.xgglass.core"
    compileSdk = 36
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
                artifactId = "core-kmp-android"
            }
        }
    }
}
