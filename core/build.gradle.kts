plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.core"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.13.2")
}
