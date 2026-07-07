plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.device.androidxr"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.core.ktx)

    // Android XR support is a preview scaffold: AndroidXrGlassesClient does not call the Jetpack XR
    // SDK yet (it resolves a projected Context via AndroidXrOptions, or fails with
    // GlassesError.Unsupported). When the projected-context path is implemented, add the verified
    // Jetpack XR coordinates here (e.g. androidx.xr.runtime / androidx.xr.arcore / androidx.xr.glimmer),
    // confirming exact group:artifact:version against the current Jetpack XR release first.
}
