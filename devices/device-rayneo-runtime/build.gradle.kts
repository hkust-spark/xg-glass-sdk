plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.device.rayneo.runtime"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))
}
