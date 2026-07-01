plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.appcontract"
}

dependencies {
    api(project(":core"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.13.2")
}
