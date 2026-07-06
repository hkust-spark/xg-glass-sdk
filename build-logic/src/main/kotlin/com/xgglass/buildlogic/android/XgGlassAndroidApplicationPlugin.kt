package com.xgglass.buildlogic.android

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class XgGlassAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.application")

        val android = project.extensions.getByType(ApplicationExtension::class.java)
        android.applyXgGlassAndroidDefaults()
        // NOTE: we intentionally do not set targetSdk/applicationId/version here;
        // keep those owned by the application module.
    }
}
