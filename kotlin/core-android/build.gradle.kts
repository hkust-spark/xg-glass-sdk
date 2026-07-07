plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

android {
    namespace = "com.xgglass.core.android"
}

dependencies {
    api(project(":core"))

    implementation(libs.tink.android)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.junit4)
}
