plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.rayneo.runtime"
}

dependencies {
    api(project(":core"))
}
