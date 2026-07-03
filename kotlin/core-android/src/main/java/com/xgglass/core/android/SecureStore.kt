package com.xgglass.core.android

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

/** Encrypted-at-rest key->String store for secrets (API keys, client secrets). */
interface SecureStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()

    companion object {
        /**
         * Create a SecureStore whose ciphertext lives in a private SharedPreferences
         * file named [name]. Backed by a Tink AES-256-GCM keyset wrapped by an
         * Android Keystore master key.
         */
        fun create(context: Context, name: String): SecureStore {
            return createTinkSecureStore(context, name)
        }
    }
}

internal interface BytesBacking {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray?)
    fun keys(): Set<String>
}

internal interface Cryptor {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

internal class AeadSecureStore(
    private val backing: BytesBacking,
    private val cryptor: Cryptor,
) : SecureStore {
    override fun getString(key: String): String? {
        val ciphertext = backing.read(key) ?: return null
        val associatedData = associatedDataFor(key)
        return try {
            val plaintext = cryptor.decrypt(ciphertext, associatedData)
            String(plaintext, StandardCharsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            backing.write(key, null)
            null
        }
    }

    override fun putString(key: String, value: String) {
        val associatedData = associatedDataFor(key)
        val plaintext = value.toByteArray(StandardCharsets.UTF_8)
        backing.write(key, cryptor.encrypt(plaintext, associatedData))
    }

    override fun remove(key: String) {
        backing.write(key, null)
    }

    override fun clear() {
        backing.keys().toList().forEach { key ->
            backing.write(key, null)
        }
    }

    private fun associatedDataFor(key: String): ByteArray {
        return key.toByteArray(StandardCharsets.UTF_8)
    }
}
