plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // Single source of truth for pure Omi button protocol; compiled into iOS klibs/XCFramework only, never with Android Maven on one classpath, and becomes commonMain when Omi modules merge in 0.3.
        commonMain {
            kotlin.srcDir("../omi-shared/src/main/kotlin")
            dependencies {
                api(project(":core"))
            }
        }
        commonTest {
            kotlin.srcDir("../omi-shared/src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
