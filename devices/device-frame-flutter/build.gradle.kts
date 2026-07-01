plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.device.frame.flutter"
}

dependencies {
    api(project(":core"))
    // Intentionally no direct Flutter dependency here.
    // The host app provides a FrameFlutterBridge implementation that talks to its embedded Flutter module.
}
