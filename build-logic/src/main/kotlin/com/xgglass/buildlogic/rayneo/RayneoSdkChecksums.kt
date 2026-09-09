package com.xgglass.buildlogic.rayneo

import java.io.File
import java.security.MessageDigest

/** Optional, developer-maintained pin for proprietary SDK files that cannot be redistributed. */
internal object RayneoSdkChecksums {
    const val MANIFEST_NAME = "rayneo-sdk.sha256"

    fun validate(directory: File, aars: List<File>) {
        val manifest = File(directory, MANIFEST_NAME)
        if (!manifest.exists()) return
        require(manifest.isFile) { "RayNeo checksum manifest is not a file: $manifest" }
        val expected = linkedMapOf<String, String>()
        manifest.readLines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                val match = Regex("^([0-9a-fA-F]{64})\\s+\\*?([^/\\\\]+)$").matchEntire(line)
                require(match != null) { "Malformed $MANIFEST_NAME line ${index + 1}: expected SHA-256 and an AAR filename" }
                val filename = match.groupValues[2]
                require(filename.endsWith(".aar", ignoreCase = true)) { "Only AAR filenames are allowed in $MANIFEST_NAME: $filename" }
                require(expected.put(filename, match.groupValues[1].lowercase()) == null) {
                    "Duplicate filename in $MANIFEST_NAME: $filename"
                }
            }
        }
        val actualNames = aars.map { it.name }.toSet()
        require(expected.keys == actualNames) {
            "$MANIFEST_NAME must cover exactly the AAR files in $directory. " +
                "Missing: ${actualNames - expected.keys}; absent files: ${expected.keys - actualNames}"
        }
        aars.forEach { aar ->
            val digest = MessageDigest.getInstance("SHA-256")
            aar.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            require(actual == expected[aar.name]) {
                "RayNeo SHA-256 mismatch for ${aar.name}: expected ${expected[aar.name]}, got $actual. " +
                    "Verify the vendor download before updating $MANIFEST_NAME."
            }
        }
    }
}
