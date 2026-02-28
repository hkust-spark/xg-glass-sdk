package com.universalglasses.buildlogic.android

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class UniversalGlassesAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.application")
        project.pluginManager.apply("org.jetbrains.kotlin.android")

        val android = project.extensions.getByType(ApplicationExtension::class.java)
        android.applyUniversalGlassesAndroidDefaults()
        // NOTE: we intentionally do not set targetSdk/applicationId/version here;
        // keep those owned by the application module.

        project.tasks.withType(KotlinCompile::class.java).configureEach {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
}
