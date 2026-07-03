package com.xgglass.core.android

import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureStoreTest {
    @Test
    fun `putString and getString round trip encrypted value`() {
        // Arrange
        val backing = FakeBacking()
        val store = AeadSecureStore(backing, FakeCryptor())

        // Act
        store.putString("api_key", "secret-value")
        val value = store.getString("api_key")

        // Assert
        assertEquals("secret-value", value)
        assertFalse(backing.raw("api_key").contentEquals("secret-value".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `overwrite replaces stored value`() {
        // Arrange
        val backing = FakeBacking()
        val store = AeadSecureStore(backing, FakeCryptor())

        // Act
        store.putString("client_secret", "first")
        store.putString("client_secret", "second")

        // Assert
        assertEquals("second", store.getString("client_secret"))
    }

    @Test
    fun `getString returns null for missing value`() {
        // Arrange
        val store = AeadSecureStore(FakeBacking(), FakeCryptor())

        // Act / Assert
        assertNull(store.getString("missing"))
    }

    @Test
    fun `remove deletes stored value`() {
        // Arrange
        val store = AeadSecureStore(FakeBacking(), FakeCryptor())

        // Act
        store.putString("api_key", "secret")
        store.remove("api_key")

        // Assert
        assertNull(store.getString("api_key"))
    }

    @Test
    fun `clear deletes all stored values`() {
        // Arrange
        val backing = FakeBacking()
        val store = AeadSecureStore(backing, FakeCryptor())

        // Act
        store.putString("first", "one")
        store.putString("second", "two")
        store.clear()

        // Assert
        assertTrue(backing.keys().isEmpty())
    }

    @Test
    fun `ciphertext is bound to associated key`() {
        // Arrange
        val backing = FakeBacking()
        val store = AeadSecureStore(backing, FakeCryptor())

        // Act
        store.putString("first", "same plaintext")
        store.putString("second", "same plaintext")

        // Assert
        assertFalse(backing.raw("first").contentEquals(backing.raw("second")))
    }

    @Test
    fun `decrypt failure drops corrupt entry and returns null`() {
        // Arrange
        val backing = FakeBacking()
        backing.write("api_key", byteArrayOf(1, 2, 3))
        val store = AeadSecureStore(backing, FailingDecryptCryptor())

        // Act
        val value = store.getString("api_key")

        // Assert
        assertNull(value)
        assertFalse("api_key" in backing.keys())
    }

    @Test
    fun `reading a value under the wrong key is rejected and purges the entry`() {
        // Arrange
        val backing = FakeBacking()
        val store = AeadSecureStore(backing, AuthenticatingCryptor())
        store.putString("alpha", "secret")
        backing.write("beta", backing.raw("alpha").copyOf())

        // Act
        val value = store.getString("beta")

        // Assert
        assertNull(value)
        assertFalse("beta" in backing.keys())
    }

    private class FakeBacking : BytesBacking {
        private val values = mutableMapOf<String, ByteArray>()

        override fun read(key: String): ByteArray? = values[key]

        override fun write(key: String, value: ByteArray?) {
            if (value == null) {
                values.remove(key)
            } else {
                values[key] = value
            }
        }

        override fun keys(): Set<String> = values.keys.toSet()

        fun raw(key: String): ByteArray {
            return values.getValue(key)
        }
    }

    private class FakeCryptor : Cryptor {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val aadByte = associatedData.firstOrNull() ?: 0
            val out = ByteArray(plaintext.size + 1)
            out[0] = associatedData.size.toByte()
            plaintext.reversedArray().forEachIndexed { index, byte ->
                out[index + 1] = byte.xor(0x5A.toByte()).xor(aadByte)
            }
            return out
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val aadByte = associatedData.firstOrNull() ?: 0
            val encrypted = ciphertext.drop(1).toByteArray()
            val out = ByteArray(encrypted.size)
            encrypted.forEachIndexed { index, byte ->
                out[index] = byte.xor(0x5A.toByte()).xor(aadByte)
            }
            return out.reversedArray()
        }

        private fun Byte.xor(other: Byte): Byte = (toInt() xor other.toInt()).toByte()
    }

    private class FailingDecryptCryptor : Cryptor {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            return plaintext
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            throw GeneralSecurityException("bad ciphertext")
        }
    }

    private class AuthenticatingCryptor : Cryptor {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val out = ByteArray(associatedData.size + 1 + plaintext.size)
            associatedData.copyInto(out)
            out[associatedData.size] = 0
            plaintext.copyInto(out, destinationOffset = associatedData.size + 1)
            return out
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val separator = ciphertext.indexOf(0)
            if (separator < 0) throw GeneralSecurityException("missing aad separator")
            val embeddedAad = ciphertext.copyOfRange(0, separator)
            if (!embeddedAad.contentEquals(associatedData)) {
                throw GeneralSecurityException("associated data mismatch")
            }
            return ciphertext.copyOfRange(separator + 1, ciphertext.size)
        }
    }
}
