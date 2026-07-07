plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka)
}

dokka {
    moduleName.set("xg.glass SDK")
    modulePath.set("")
}

dependencies {
    dokka(project(":universal"))
    dokka(project(":core"))
    dokka(project(":core-android"))
    dokka(project(":app-contract"))
    dokka(project(":device-rokid"))
    dokka(project(":device-frame-flutter"))
    dokka(project(":device-rayneo-installer"))
    dokka(project(":device-rayneo-runtime"))
    dokka(project(":device-inmo-runtime"))
    dokka(project(":device-simulator"))
    dokka(project(":device-omi"))
    dokka(project(":device-even"))
    if (findProject(":device-meta") != null) {
        dokka(project(":device-meta"))
    }
}

allprojects {
    group = "io.github.hkust-spark"
    version = providers.gradleProperty("version").get()
}
