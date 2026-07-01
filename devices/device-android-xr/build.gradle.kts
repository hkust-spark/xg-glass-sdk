plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.device.androidxr"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("androidx.core:core-ktx:1.13.1")

    // TODO(android-xr): developer-preview coordinate - verify exact group:artifact:version against the current Jetpack XR SDK release.
    implementation("androidx.xr.runtime:runtime:1.0.0-alpha15")
    // TODO(android-xr): developer-preview coordinate - verify exact group:artifact:version against the current Jetpack XR SDK release.
    implementation("androidx.xr.projected:projected:1.0.0-alpha09")
    // TODO(android-xr): developer-preview coordinate - verify exact group:artifact:version against the current Jetpack XR SDK release.
    implementation("androidx.xr.glimmer:glimmer:1.0.0-alpha13")
    // TODO(android-xr): developer-preview coordinate - verify exact group:artifact:version against the current Jetpack XR SDK release.
    implementation("androidx.xr.glimmer:glimmer-google-fonts:1.0.0-alpha13")
    // TODO(android-xr): developer-preview coordinate - verify exact group:artifact:version against the current Jetpack XR SDK release.
    implementation("androidx.xr.arcore:arcore:1.0.0-alpha14")

    // TODO(android-xr): developer-preview coordinate - verify exact group:artifact:version against the current Jetpack XR SDK release.
    // testImplementation("androidx.xr.projected:projected-testing:1.0.0-alpha09")
}
