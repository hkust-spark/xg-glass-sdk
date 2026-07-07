plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.device.meta"

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":core-android"))

    api(libs.mwdat.core)
    api(libs.mwdat.camera)
    api(libs.mwdat.display)

    api(libs.androidx.appcompat)
    api(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)

    androidTestImplementation(libs.mwdat.mockdevice)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
}
