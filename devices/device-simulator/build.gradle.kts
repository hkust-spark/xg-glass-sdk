plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.sim"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Camera (on Android Emulator, backed by host webcam passthrough)
    api(libs.androidx.camera.core)
    api(libs.androidx.camera.camera2)
    api(libs.androidx.camera.lifecycle)

    api("androidx.appcompat:appcompat:1.7.1")
    api(libs.androidx.core.ktx)
}
