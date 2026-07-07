plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.rokid"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    // Rokid CXR-M SDK
    api("com.rokid.cxr:client-m:1.0.4") {
        // Avoid pulling the sources artifact transitively; keeps dependency graph smaller.
        exclude(group = "com.rokid.cxr", module = "client-m-sources")
    }

    // We keep JSON small and stable for custom view layout.
    api(libs.gson)

    // Required because the adapter takes an AppCompatActivity and uses AndroidX APIs.
    api(libs.androidx.appcompat)
    api(libs.androidx.core.ktx)
}
