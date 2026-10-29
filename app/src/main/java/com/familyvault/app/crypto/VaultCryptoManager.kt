package com.familyvault.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultCryptoManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun getOrCreateHardwareSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) return existingEntry.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
  KEY_ALIAS,
  KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
  .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
  .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
  .setKeySize(256)
  .setRandomizedEncryptionRequired(true)
  .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptWithHardwareKey(plainBytes: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateHardwareSecretKey())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)

        val combined = ByteArray(1 + iv.size + cipherBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
        return combined
    }

    private fun decryptWithHardwareKey(combined: ByteArray, associatedData: ByteArray): ByteArray {
        val ivSize = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivSize)
        val cipherBytes = combined.copyOfRange(1 + ivSize, combined.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateHardwareSecretKey(), spec)
        cipher.updateAAD(associatedData)
        return cipher.doFinal(cipherBytes)
    }

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingB64 = prefs.getString(ENCRYPTED_DB_PASS_KEY, null)
        if (existingB64 != null) {
  try {
      val combined = Base64.decode(existingB64, Base64.NO_WRAP)
      return decryptWithHardwareKey(combined, DB_AAD)
  } catch (_: Throwable) {
      prefs.edit().remove(ENCRYPTED_DB_PASS_KEY).commit()
  }
        }

        val newPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted = encryptWithHardwareKey(newPassphrase, DB_AAD)
        prefs.edit()
  .putString(ENCRYPTED_DB_PASS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
  .commit()
        return newPassphrase
    }

    fun encryptStreamToVaultFile(
        inputStream: InputStream,
        destinationFile: File,
        associatedData: String
    ): String {
        val rawBytes = inputStream.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(rawBytes)
        val encryptedBytes = encryptWithHardwareKey(rawBytes, associatedData.toByteArray())
        destinationFile.writeBytes(encryptedBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun openDecryptedStream(encryptedFile: File, associatedData: String): InputStream {
        val encryptedBytes = encryptedFile.readBytes()
        val decryptedBytes = decryptWithHardwareKey(encryptedBytes, associatedData.toByteArray())
        return ByteArrayInputStream(decryptedBytes)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "family_vault_hw_aes256_v2"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFS_FILE = "family_vault_secure_prefs"
        private const val ENCRYPTED_DB_PASS_KEY = "wrapped_sqlcipher_pass_v2"
        private val DB_AAD = "SQLCipher_FamilyVault_v2".toByteArray()
    }
}
