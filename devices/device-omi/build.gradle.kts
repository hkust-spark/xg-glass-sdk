plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.omi"
}

dependencies {
    api(project(":core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
