package com.universalglasses.buildlogic.android

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class UniversalGlassesAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.library")
        project.pluginManager.apply("org.jetbrains.kotlin.android")

        val android = project.extensions.getByType(LibraryExtension::class.java)
        android.applyUniversalGlassesAndroidDefaults()

        // Keep Kotlin JVM target consistent across modules.
        project.tasks.withType(KotlinCompile::class.java).configureEach {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
}
