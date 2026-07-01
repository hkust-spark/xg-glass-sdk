package com.xgglass.core.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

internal fun createTinkSecureStore(context: Context, name: String): SecureStore {
    return TinkSecureStoreFactory.create(context, name)
}

private object TinkSecureStoreFactory {
    private const val KEYSET_NAME = "xgglass__tink_keyset"
    private const val KEYSET_PREFS = "xgglass__tink_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://xgglass__tink_master_key"

    @Volatile
    private var registered = false

    fun create(context: Context, name: String): SecureStore {
        val appContext = context.applicationContext
        registerTink()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        val aead = keysetHandle.getPrimitive(Aead::class.java)
        val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        return AeadSecureStore(
            backing = SharedPreferencesBytesBacking(prefs),
            cryptor = TinkCryptor(aead),
        )
    }

    private fun registerTink() {
        if (registered) return
        synchronized(this) {
            if (!registered) {
                AeadConfig.register()
                registered = true
            }
        }
    }
}

private class TinkCryptor(
    private val aead: Aead,
) : Cryptor {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        return aead.encrypt(plaintext, associatedData)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        return aead.decrypt(ciphertext, associatedData)
    }
}

private class SharedPreferencesBytesBacking(
    private val prefs: SharedPreferences,
) : BytesBacking {
    override fun read(key: String): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun write(key: String, value: ByteArray?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putString(key, Base64.encodeToString(value, Base64.NO_WRAP))
        }
        editor.apply()
    }

    override fun keys(): Set<String> {
        return prefs.all.keys.toSet()
    }
}
