plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.universal"
}

dependencies {
    // Expose the stable API surface
    api(project(":core"))
    // Expose the universal "app entry" contracts (pure Kotlin API used by hosts/plugins).
    api(project(":app-contract"))

    // Always include Rokid implementation
    api(project(":device-rokid"))

    // RayNeo: installer (phone-side) + runtime (on-glasses)
    api(project(":device-rayneo-installer"))
    api(project(":device-rayneo-runtime"))

    // Simulator implementation (virtual glasses for development/testing)
    api(project(":device-simulator"))

    // Meta Wearables (Ray-Ban Meta)
    api(project(":device-meta-wearable"))

    // Include Frame when available in this build (i.e., frame_module exists and is included).
    // For published artifacts, ensure the build pipeline always includes device-frame-embedded.
    if (project.findProject(":device-frame-embedded") != null) {
        api(project(":device-frame-embedded"))
    }
}
