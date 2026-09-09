package com.xgglass.core.android

/** Convert signed SDK PCM8 to/from Android's unsigned PCM8 without modifying caller data. */
internal fun convertPcm8Signedness(bytes: ByteArray): ByteArray =
    ByteArray(bytes.size) { (bytes[it].toInt() xor 0x80).toByte() }
