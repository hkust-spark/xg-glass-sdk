package com.universalglasses.buildlogic.rayneo

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import java.io.File

/**
 * Settings plugin: generates a RayNeo glass-host Android app module under the consumer project's build directory,
 * and includes it as a Gradle project.
 *
 * This is the piece that makes RayNeo "feel like" Frame/Rokid for app developers:
 * the extra on-glasses APK exists, but it is generated/maintained by tooling.
 */
class UniversalGlassesRayneoSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val hostProjectPath = settings.providers.gradleProperty("ugRayneoHostProjectPath").orNull ?: ":ug_rayneo_glass_host"
        val hostProjectName = hostProjectPath.removePrefix(":")

        val root = settings.settingsDir
        val generatedRoot = File(root, "build/universal_glasses/generated")
        val hostDir = File(generatedRoot, hostProjectName)

        generateHostIfNeeded(hostDir)

        settings.include(hostProjectPath)
        settings.project(hostProjectPath).projectDir = hostDir
    }

    private fun generateHostIfNeeded(hostDir: File) {
        val marker = File(hostDir, ".ug_template_version")
        val existingVersion = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        if (existingVersion == RayneoHostTemplate.TEMPLATE_VERSION) return

        hostDir.mkdirs()

        // Write template files (overwrite when version changes).
        RayneoHostTemplate.files().forEach { tf ->
            val f = File(hostDir.parentFile, tf.relativePath)
            f.parentFile.mkdirs()
            f.writeText(tf.content)
        }

        marker.writeText(RayneoHostTemplate.TEMPLATE_VERSION.toString())
    }
}
