package com.xgglass.buildlogic.rayneo

import java.io.File
import java.security.MessageDigest
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RayneoSdkIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun upgradeRemovesObsoleteAarBeforeHostCompilationAndPreservesUserLibraries() {
        val project = fixture()
        val vendor = File(project, "vendor")
        File(vendor, "MercuryAndroidSDK-0.2.3.aar").writeText("old SDK fixture")
        File(vendor, "RayNeoIPCSDK-0.1.0.aar").writeText("IPC fixture")
        val userLibrary = File(project, "xgglass_rayneo_glass_host/libs/app-owned.aar")
        userLibrary.parentFile.mkdirs()
        userLibrary.writeText("must remain intact")
        expectAars(project, "MercuryAndroidSDK-0.2.3.aar", "RayNeoIPCSDK-0.1.0.aar")

        run(project)
        File(vendor, "MercuryAndroidSDK-0.2.3.aar").delete()
        File(vendor, "MercuryAndroidSDK-0.2.6.aar").writeText("upgraded SDK fixture")
        expectAars(project, "MercuryAndroidSDK-0.2.6.aar", "RayNeoIPCSDK-0.1.0.aar")

        val second = run(project)
        val tasks = second.tasks.map { it.path }
        assertTrue(tasks.indexOf(":app:syncRayneoMercuryAars") < tasks.indexOf(":xgglass_rayneo_glass_host:compileDebugFixture"))
        val managed = File(project, "xgglass_rayneo_glass_host/build/xgglass/rayneo-libs")
        assertFalse(File(managed, "MercuryAndroidSDK-0.2.3.aar").exists())
        assertEquals("upgraded SDK fixture", File(managed, "MercuryAndroidSDK-0.2.6.aar").readText())
        assertEquals("must remain intact", userLibrary.readText())
        assertEquals("fixture APK", File(project, "app/src/main/assets/rayneo_glass_app.apk").readText())
    }

    @Test
    fun rejectsMissingIpcAndDuplicateMercuryVersions() {
        val project = fixture()
        val vendor = File(project, "vendor")
        File(vendor, "MercuryAndroidSDK-0.2.3.aar").writeText("SDK fixture")
        assertTrue(run(project, fail = true).output.contains("Expected exactly one RayNeoIPCSDK"))
        File(vendor, "RayNeoIPCSDK-0.1.0.aar").writeText("IPC fixture")
        File(vendor, "MercuryAndroidSDK-0.2.6.aar").writeText("another SDK fixture")
        assertTrue(run(project, fail = true).output.contains("Expected exactly one MercuryAndroidSDK"))
    }

    @Test
    fun pinnedSdkRejectsChangedBytesBeforeCompilation() {
        val project = fixture()
        val vendor = File(project, "vendor")
        val mercury = File(vendor, "MercuryAndroidSDK-0.2.6.aar").apply { writeText("verified SDK fixture") }
        val ipc = File(vendor, "RayNeoIPCSDK-0.1.0.aar").apply { writeText("verified IPC fixture") }
        File(vendor, RayneoSdkChecksums.MANIFEST_NAME).writeText(
            listOf(mercury, ipc).joinToString("\n") { "${sha256(it)}  ${it.name}" }
        )
        expectAars(project, mercury.name, ipc.name)
        run(project)

        mercury.writeText("different download, same filename")
        val failed = run(project, fail = true)
        assertTrue(failed.output.contains("RayNeo SHA-256 mismatch for ${mercury.name}"))
        assertTrue(failed.tasks.none { it.path.endsWith(":compileDebugFixture") })
    }

    @Test
    fun rejectsUppercaseExtensionInsteadOfSilentlyDroppingAnSdk() {
        val project = fixture()
        File(project, "vendor/MercuryAndroidSDK-0.2.6.aar").writeText("SDK fixture")
        File(project, "vendor/RayNeoIPCSDK-0.1.0.AAR").writeText("IPC fixture")
        assertTrue(run(project, fail = true).output.contains("must use the lowercase .aar extension"))
    }

    private fun fixture(): File {
        val project = temporaryFolder.newFolder()
        File(project, "vendor").mkdirs()
        File(project, "settings.gradle.kts").writeText("""
            rootProject.name = "rayneo-regression"
            include(":app", ":xgglass_rayneo_glass_host", ":xgglass_app_logic")
        """.trimIndent())
        File(project, "xgglass_app_logic").mkdirs()
        File(project, "app").mkdirs()
        File(project, "app/build.gradle.kts").writeText("""
            plugins { id("com.xgglass.rayneo.app") }
            xgRayneo {
                appEntryClass.set("fixture.Entry")
                mercuryAarDir.set(rootProject.file("vendor").absolutePath)
            }
            tasks.register("preDebugBuild")
        """.trimIndent())
        // Exercise the real generated dependency expression. The host fixture supplies only
        // the Android lifecycle tasks, avoiding a proprietary SDK or Android toolchain download.
        val template = RayneoHostTemplate.files().single { it.relativePath.endsWith("/build.gradle.kts") }.content
        val aarDependency = template.lineSequence().single { it.contains("implementation(fileTree(") }
            .trim().replaceFirst("implementation(", "add(\"implementation\", ")
        val host = File(project, "xgglass_rayneo_glass_host").apply { mkdirs() }
        File(host, "build.gradle.kts").writeText("""
            configurations.create("implementation")
            dependencies { $aarDependency }
            tasks.register("preBuild")
            tasks.register("preDebugBuild") { dependsOn("preBuild") }
            tasks.register("compileDebugFixture") {
                dependsOn("preDebugBuild")
                doLast {
                    val actual = configurations.getByName("implementation").files.map { it.name }.sorted()
                    val expected = rootProject.file("expected-aars.txt").readLines().sorted()
                    check(actual == expected) { "Host compiled with stale or missing AARs: ${'$'}actual, expected ${'$'}expected" }
                }
            }
            tasks.register("assembleDebug") {
                dependsOn("compileDebugFixture")
                doLast {
                    file("build/outputs/apk/debug/xgglass_rayneo_glass_host-debug.apk").apply {
                        parentFile.mkdirs()
                        writeText("fixture APK")
                    }
                }
            }
        """.trimIndent())
        return project
    }

    private fun expectAars(project: File, vararg names: String) {
        File(project, "expected-aars.txt").writeText(names.joinToString("\n"))
    }

    private fun run(project: File, fail: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withArguments(":app:preDebugBuild", "--stacktrace", "--offline")
        return if (fail) runner.buildAndFail() else runner.build()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
