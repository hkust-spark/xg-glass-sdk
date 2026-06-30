plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.core.android"
}

dependencies {
    api(project(":core"))
}
