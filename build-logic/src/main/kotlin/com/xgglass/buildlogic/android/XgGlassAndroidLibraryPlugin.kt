package com.xgglass.buildlogic.android

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class XgGlassAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.library")

        val android = project.extensions.getByType(LibraryExtension::class.java)
        android.applyXgGlassAndroidDefaults()
    }
}
