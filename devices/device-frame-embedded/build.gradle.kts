plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.device.frame.embedded"
}

dependencies {
    api(project(":core"))
    implementation(project(":device-frame-flutter"))

    // Built by `flutter build aar` from third_party/frame/frame_module.
    debugImplementation("com.example.frame_module:flutter_debug:1.0")
    releaseImplementation("com.example.frame_module:flutter_release:1.0")
}
