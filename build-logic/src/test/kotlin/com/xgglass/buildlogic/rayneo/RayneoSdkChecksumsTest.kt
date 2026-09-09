package com.xgglass.buildlogic.rayneo

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RayneoSdkChecksumsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun requiresExactCoverageAndRejectsDuplicateOrUnsafeFilenames() {
        val directory = temporaryFolder.newFolder()
        val aar = File(directory, "MercuryAndroidSDK.aar").apply { writeText("fixture") }
        val hash = MessageDigest.getInstance("SHA-256").digest(aar.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val manifest = File(directory, RayneoSdkChecksums.MANIFEST_NAME)
        for (content in listOf("", "$hash  missing.aar", "$hash  ../${aar.name}",
            "$hash  ${aar.name}\n$hash  ${aar.name}", "not-a-hash  ${aar.name}")) {
            manifest.writeText(content)
            assertThrows(IllegalArgumentException::class.java) { RayneoSdkChecksums.validate(directory, listOf(aar)) }
        }
        manifest.writeText("# Download verified by the developer\n$hash *${aar.name}\n")
        RayneoSdkChecksums.validate(directory, listOf(aar))
    }
}
