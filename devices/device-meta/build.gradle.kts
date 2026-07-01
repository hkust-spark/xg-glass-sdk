plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.device.meta"

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    api(libs.mwdat.core)
    api(libs.mwdat.camera)

    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
