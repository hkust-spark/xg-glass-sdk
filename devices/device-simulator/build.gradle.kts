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

    api(libs.kotlinx.coroutines.android)

    // Camera (on Android Emulator, backed by host webcam passthrough)
    api(libs.androidx.camera.core)
    api(libs.androidx.camera.camera2)
    api(libs.androidx.camera.lifecycle)

    api(libs.androidx.appcompat)
    api(libs.androidx.core.ktx)
}
