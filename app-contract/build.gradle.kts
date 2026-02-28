plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.appcontract"
}

dependencies {
    api(project(":core"))
}
