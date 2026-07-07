plugins {
    id("com.xgglass.android.library")
    id("com.xgglass.maven-publish")
}

val universalDokkaDir = layout.buildDirectory.dir("dokka-support/universal")
val universalDokkaOverview = universalDokkaDir.map { it.file("universal.md") }
val universalDokkaPackageDoc = universalDokkaDir.map { it.file("com.xgglass.universal.md") }
val universalDokkaSource = universalDokkaDir.map { it.file("src/com/xgglass/universal/package-info.java") }
val writeUniversalDokkaSources = tasks.register("writeUniversalDokkaSources") {
    outputs.dir(universalDokkaDir)
    doLast {
        universalDokkaOverview.get().asFile.writeText(
            """
            # Module xgglass-universal

            Published aggregate Android artifact that re-exports the core API, app contract,
            and openly distributable Android device adapters.
            """.trimIndent() + "\n",
        )
        universalDokkaPackageDoc.get().asFile.writeText(
            """
            # Package com.xgglass.universal

            Marker package for the published xgglass-universal aggregate artifact.
            """.trimIndent() + "\n",
        )
        universalDokkaSource.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                /**
                 * Marker package for the published xgglass-universal aggregate artifact.
                 */
                package com.xgglass.universal;
                """.trimIndent() + "\n",
            )
        }
    }
}

dokka {
    dokkaSourceSets.configureEach {
        includes.from(
            writeUniversalDokkaSources.map { universalDokkaOverview.get().asFile },
            writeUniversalDokkaSources.map { universalDokkaPackageDoc.get().asFile },
        )
        sourceRoots.from(writeUniversalDokkaSources.map { universalDokkaSource.get().asFile.parentFile })
        skipEmptyPackages.set(false)
    }
}

android {
    namespace = "com.xgglass.universal"
}

dependencies {
    // Expose the stable API surface
    api(project(":core"))
    // Expose SecureStore + shared Android helpers
    api(project(":core-android"))
    // Expose the universal "app entry" contracts (pure Kotlin API used by hosts/plugins).
    api(project(":app-contract"))

    // Always include Rokid implementation
    api(project(":device-rokid"))

    // RayNeo: installer (phone-side) + runtime (on-glasses)
    api(project(":device-rayneo-installer"))
    api(project(":device-rayneo-runtime"))

    // INMO Air3 on-glasses runtime adapter
    api(project(":device-inmo-runtime"))

    // Simulator implementation (virtual glasses for development/testing)
    api(project(":device-simulator"))

    // Omi implementation (audio-focused BLE glasses)
    api(project(":device-omi"))

    // Even Realities G1 implementation (dual-BLE display, microphone, touch)
    api(project(":device-even"))

    // Frame bridge API. The embedded Flutter host wrapper remains app-local and is not published.
    api(project(":device-frame-flutter"))
}
