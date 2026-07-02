import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
