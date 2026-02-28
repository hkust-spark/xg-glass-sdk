plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.rayneo.installer"
}

dependencies {
    api(project(":core"))

    // ADB-over-TCP client library (talks to adbd:5555 directly)
    api("com.tananaev:adblib:1.3")
}
