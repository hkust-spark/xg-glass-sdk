plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.omi"
}

dependencies {
    api(project(":core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
