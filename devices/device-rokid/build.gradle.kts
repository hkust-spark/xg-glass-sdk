plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.rokid"
}

dependencies {
    api(project(":core"))

    // Rokid CXR-M SDK
    api("com.rokid.cxr:client-m:1.0.4") {
        // Avoid pulling the sources artifact transitively; keeps dependency graph smaller.
        exclude(group = "com.rokid.cxr", module = "client-m-sources")
    }

    // We keep JSON small and stable for custom view layout.
    api("com.google.code.gson:gson:2.10.1")

    // Required because the adapter takes an AppCompatActivity and uses AndroidX APIs.
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.core:core-ktx:1.13.1")
}
