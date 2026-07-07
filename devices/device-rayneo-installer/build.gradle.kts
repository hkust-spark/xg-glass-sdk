plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.rayneo.installer"
}

dependencies {
    api(project(":core"))

    // ADB-over-TCP client library (talks to adbd:5555 directly)
    api(libs.adblib)
}
