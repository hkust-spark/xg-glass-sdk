package com.xgglass.buildlogic.publish

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.MavenPomDeveloper
import org.gradle.api.publish.maven.MavenPomDeveloperSpec
import org.gradle.api.publish.maven.MavenPomLicense
import org.gradle.api.publish.maven.MavenPomLicenseSpec
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPomScm

class XgGlassMavenPublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.vanniktech.maven.publish")

        project.extensions.configure(
            MavenPublishBaseExtension::class.java,
            object : Action<MavenPublishBaseExtension> {
                override fun execute(publishing: MavenPublishBaseExtension) {
                    publishing.publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
                    if (project.shouldSignPublications()) {
                        publishing.signAllPublications()
                    }
                    publishing.coordinates(
                        groupId = "io.github.hkust-spark",
                        artifactId = "xgglass-${project.name}",
                        version = project.version.toString(),
                    )
                    publishing.pom(object : Action<MavenPom> {
                        override fun execute(pom: MavenPom) {
                            pom.name.set("XG Glass SDK ${project.name}")
                            pom.description.set("XG Glass SDK module ${project.name}.")
                            pom.inceptionYear.set("2024")
                            pom.url.set("https://github.com/hkust-spark/xg-glass-sdk")
                            pom.licenses(object : Action<MavenPomLicenseSpec> {
                                override fun execute(licenses: MavenPomLicenseSpec) {
                                    licenses.license(object : Action<MavenPomLicense> {
                                        override fun execute(license: MavenPomLicense) {
                                            license.name.set("The Apache License, Version 2.0")
                                            license.url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                            license.distribution.set("repo")
                                        }
                                    })
                                }
                            })
                            pom.developers(object : Action<MavenPomDeveloperSpec> {
                                override fun execute(developers: MavenPomDeveloperSpec) {
                                    developers.developer(object : Action<MavenPomDeveloper> {
                                        override fun execute(developer: MavenPomDeveloper) {
                                            developer.id.set("hkust-spark")
                                            developer.name.set("HKUST SPARK")
                                            developer.url.set("https://github.com/hkust-spark")
                                        }
                                    })
                                }
                            })
                            pom.scm(object : Action<MavenPomScm> {
                                override fun execute(scm: MavenPomScm) {
                                    scm.connection.set("scm:git:https://github.com/hkust-spark/xg-glass-sdk.git")
                                    scm.developerConnection.set("scm:git:ssh://git@github.com/hkust-spark/xg-glass-sdk.git")
                                    scm.url.set("https://github.com/hkust-spark/xg-glass-sdk")
                                }
                            })
                        }
                    })
                }
            },
        )

        project.gradle.projectsEvaluated {
            project.extensions.configure(
                PublishingExtension::class.java,
                object : Action<PublishingExtension> {
                    override fun execute(publishing: PublishingExtension) {
                        publishing.publications.withType(MavenPublication::class.java).configureEach(
                            object : Action<MavenPublication> {
                                override fun execute(publication: MavenPublication) {
                                    if (!publication.artifactId.startsWith("xgglass-")) {
                                        publication.artifactId = "xgglass-${publication.artifactId}"
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun Project.shouldSignPublications(): Boolean {
        val signingRequested = providers.gradleProperty("xgGlassSignPublications")
            .map(String::toBoolean)
            .getOrElse(false)
        if (!signingRequested) {
            return false
        }
        return providers.gradleProperty("signingInMemoryKey").isPresent ||
            providers.gradleProperty("signing.secretKeyRingFile").isPresent
    }
}
