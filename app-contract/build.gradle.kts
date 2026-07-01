plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.appcontract"
}

dependencies {
    api(project(":core"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.13.2")
}
