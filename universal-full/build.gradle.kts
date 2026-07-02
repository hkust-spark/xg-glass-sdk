plugins {
    id("com.xgglass.android.library")
}

android {
    namespace = "com.xgglass.universalfull"
}

dependencies {
    api(project(":universal"))
    if (project.findProject(":device-meta") != null) {
        api(project(":device-meta"))
    }
    if (project.findProject(":device-frame-embedded") != null) {
        api(project(":device-frame-embedded"))
    }
}
