plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.device.sim"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Camera (on Android Emulator, backed by host webcam passthrough)
    api("androidx.camera:camera-core:1.3.4")
    api("androidx.camera:camera-camera2:1.3.4")
    api("androidx.camera:camera-lifecycle:1.3.4")

    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.core:core-ktx:1.13.1")
}
