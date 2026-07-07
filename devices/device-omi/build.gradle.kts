plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.omi"

    // Single source of truth for pure Omi button protocol; compiled into the Android AAR only, never with iOS on one classpath, and becomes commonMain when Omi modules merge in 0.3.
    sourceSets {
        getByName("main") {
            kotlin.directories.add("../omi-shared/src/main/kotlin")
        }
        getByName("test") {
            kotlin.directories.add("../omi-shared/src/test/kotlin")
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
