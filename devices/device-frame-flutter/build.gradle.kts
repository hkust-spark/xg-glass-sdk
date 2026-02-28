plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.frame.flutter"
}

dependencies {
    api(project(":core"))
    // Intentionally no direct Flutter dependency here.
    // The host app provides a FrameFlutterBridge implementation that talks to its embedded Flutter module.
}
