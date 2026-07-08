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

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
