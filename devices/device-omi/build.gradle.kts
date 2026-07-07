plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.omi"
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
