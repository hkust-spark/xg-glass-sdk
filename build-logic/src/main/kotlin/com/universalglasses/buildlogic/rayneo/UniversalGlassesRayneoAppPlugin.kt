package com.universalglasses.buildlogic.rayneo

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import java.io.File
import javax.inject.Inject

abstract class UniversalGlassesRayneoExtension @Inject constructor(objects: ObjectFactory) {
    val hostProjectPath: Property<String> = objects.property(String::class.java).convention(":ug_rayneo_glass_host")
    val logicProjectPath: Property<String> = objects.property(String::class.java).convention(":ug_app_logic")
    val appEntryClass: Property<String> = objects.property(String::class.java)
    /** Directory containing RayNeo/Mercury AARs (e.g., MercuryAndroidSDK*.aar + RayNeoIPCSDK*.aar). */
    val mercuryAarDir: Property<String> = objects.property(String::class.java)
    val assetApkName: Property<String> = objects.property(String::class.java).convention("rayneo_glass_app.apk")
    val variant: Property<String> = objects.property(String::class.java).convention("debug")
}

/**
 * Project plugin: applied to the phone-side app module.
 *
 * It:
 * - ensures the generated RayNeo glass-host project has the correct placeholders (entry class + logic module)
 * - builds the glass-host APK
 * - copies it into the phone app's assets so `connect()` can install it
 */
class UniversalGlassesRayneoAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("ugRayneo", UniversalGlassesRayneoExtension::class.java)

        project.afterEvaluate {
            val hostPath = ext.hostProjectPath.get()
            val hostProject = project.rootProject.findProject(hostPath)
                ?: error("RayNeo host project not found: $hostPath. Did you apply com.universalglasses.rayneo.settings in settings.gradle(.kts)?")

            val logicPath = ext.logicProjectPath.get()
            project.rootProject.findProject(logicPath)
                ?: error("RayNeo logic project not found: $logicPath. Create this module for shared business logic.")

            val entryClass = ext.appEntryClass.orNull?.trim().orEmpty()
            require(entryClass.isNotBlank()) { "ugRayneo.appEntryClass is required (fully-qualified UniversalAppEntry class name)." }

            // Patch the generated host module files (no AGP API dependency needed).
            patchHostFiles(hostProject.projectDir, logicProjectPath = logicPath, appEntryClass = entryClass)

            // Sync vendor SDK AARs into the generated host module.
            val mercuryDir = ext.mercuryAarDir.orNull
                ?.trim()
                .orEmpty()
                .ifBlank { findDefaultMercuryAarDir(project).orEmpty() }
            require(mercuryDir.isNotBlank()) {
                "ugRayneo.mercuryAarDir is required to enable RayNeo UI/navigation (Mercury SDK). " +
                    "Point it to a directory containing MercuryAndroidSDK*.aar and RayNeoIPCSDK*.aar.\n" +
                    "Tried defaults: third_party/rayneo/aar, vendor/rayneo/aar, ../universal_glasses/third_party/rayneo/aar, ../../universal_glasses/third_party/rayneo/aar"
            }
            val mercurySrcDir = File(mercuryDir)
            require(mercurySrcDir.exists() && mercurySrcDir.isDirectory) { "ugRayneo.mercuryAarDir is not a directory: $mercuryDir" }

            val variant = ext.variant.get()
            val variantCap = variant.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
            val hostApk = File(hostProject.projectDir, "build/outputs/apk/$variant/${hostProject.name}-$variant.apk")

            val assetsDir = File(project.projectDir, "src/main/assets")
            val hostLibsDir = File(hostProject.projectDir, "libs")
            val syncMercuryAars = project.tasks.register(
                "syncRayneoMercuryAars",
                Copy::class.java,
                object : org.gradle.api.Action<Copy> {
                    override fun execute(t: Copy) {
                        t.from(File(mercurySrcDir, "."))
                        t.include("*.aar")
                        t.into(hostLibsDir)
                    }
                }
            )
            val copyTask = project.tasks.register(
                "copyRayneoHostApk",
                Copy::class.java,
                object : org.gradle.api.Action<Copy> {
                    override fun execute(t: Copy) {
                        t.dependsOn(syncMercuryAars)
                        t.dependsOn("${hostProject.path}:assemble$variantCap")
                        t.from(hostApk)
                        t.into(assetsDir)
                        t.rename { _: String -> ext.assetApkName.get() }
                    }
                }
            )

            // Ensure the APK is present before building/running the phone app.
            project.tasks.matching { it.name == "pre${variantCap}Build" }.configureEach {
                dependsOn(copyTask)
            }
        }
    }

    private fun findDefaultMercuryAarDir(project: Project): String? {
        // Heuristics for common repo layouts:
        // - xg-glass init template might place vendor AARs under consumer root: third_party/rayneo/aar (preferred)
        // - keep vendor/rayneo/aar as backward compatible fallback
        // - in-repo development might keep vendor AARs under the SDK checkout: ../../universal_glasses/third_party/rayneo/aar
        val root = project.rootProject.rootDir
        val candidates = listOf(
            File(root, "third_party/rayneo/aar"),
            File(root, "vendor/rayneo/aar"),
            File(root, "../universal_glasses/third_party/rayneo/aar"),
            File(root, "../../universal_glasses/third_party/rayneo/aar"),
            File(root, "vendor/rayneo/aar"),
            File(root, "../universal_glasses/vendor/rayneo/aar"),
            File(root, "../../universal_glasses/vendor/rayneo/aar"),
        )
        return candidates
            .firstOrNull { dir ->
                dir.isDirectory && (dir.listFiles()?.any { it.isFile && it.extension.equals("aar", ignoreCase = true) } == true)
            }
            ?.absolutePath
    }

    private fun patchHostFiles(hostDir: File, logicProjectPath: String, appEntryClass: String) {
        val buildFile = File(hostDir, "build.gradle.kts")
        val manifestFile = File(hostDir, "src/main/AndroidManifest.xml")

        if (buildFile.exists()) {
            val updated = buildFile.readText()
                .replace("__UG_LOGIC_PROJECT__", logicProjectPath)
            buildFile.writeText(updated)
        }

        if (manifestFile.exists()) {
            val updated = manifestFile.readText()
                .replace("__UG_APP_ENTRY_CLASS__", appEntryClass)
            manifestFile.writeText(updated)
        }
    }
}
