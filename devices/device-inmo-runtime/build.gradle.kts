plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.inmo.runtime"
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    testImplementation(kotlin("test"))
}
