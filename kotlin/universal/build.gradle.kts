plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.universal"
}

dependencies {
    // Expose the stable API surface
    api(project(":core"))
    // Expose SecureStore + shared Android helpers
    api(project(":core-android"))
    // Expose the universal "app entry" contracts (pure Kotlin API used by hosts/plugins).
    api(project(":app-contract"))

    // Always include Rokid implementation
    api(project(":device-rokid"))

    // RayNeo: installer (phone-side) + runtime (on-glasses)
    api(project(":device-rayneo-installer"))
    api(project(":device-rayneo-runtime"))

    // INMO Air3 on-glasses runtime adapter
    api(project(":device-inmo-runtime"))

    // Simulator implementation (virtual glasses for development/testing)
    api(project(":device-simulator"))

    // Omi implementation (audio-focused BLE glasses)
    api(project(":device-omi"))

    // Even Realities G1 implementation (dual-BLE display, microphone, touch)
    api(project(":device-even"))

    // Frame bridge API. The embedded Flutter host wrapper remains app-local and is not published.
    api(project(":device-frame-flutter"))
}
