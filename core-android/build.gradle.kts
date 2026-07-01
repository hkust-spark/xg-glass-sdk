plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.core.android"
}

dependencies {
    api(project(":core"))
}
