plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.sim"
}

dependencies {
    api(project(":core"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Camera (works on Android Emulator with webcam passthrough)
    api("androidx.camera:camera-core:1.3.4")
    api("androidx.camera:camera-camera2:1.3.4")
    api("androidx.camera:camera-lifecycle:1.3.4")

    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.core:core-ktx:1.13.1")
}
