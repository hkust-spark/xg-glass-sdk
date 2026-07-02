plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.rayneo.runtime"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))
}
