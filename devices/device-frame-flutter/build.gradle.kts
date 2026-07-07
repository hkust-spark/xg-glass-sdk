plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.frame.flutter"
}

dependencies {
    api(project(":core"))
    // Intentionally no direct Flutter dependency here.
    // The host app provides a FrameFlutterBridge implementation that talks to its embedded Flutter module.

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(libs.junit4)
}
