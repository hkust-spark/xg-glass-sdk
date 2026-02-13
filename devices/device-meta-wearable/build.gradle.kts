plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.metawearable"
}

dependencies {
    api(project(":core"))

    // Meta Wearables DAT
    api(libs.mwdat.core)
    api(libs.mwdat.camera)
    
    // We keep JSON small and stable for custom view layout.
    api("com.google.code.gson:gson:2.10.1")

    // Required because the adapter takes an AppCompatActivity and uses AndroidX APIs.
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.core:core-ktx:1.13.1")
}
