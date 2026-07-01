plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.core.android"
}

dependencies {
    api(project(":core"))

    implementation("com.google.crypto.tink:tink-android:1.19.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.13.2")
}
